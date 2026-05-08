"""Privacy Warden Tank — FastAPI entrypoint.

OpenClaw layer mapping (HTTP/WS surface):
  L1 Communication      -> /webhook/whatsapp (in/out via Meta API)
  L2 Channel Adapter    -> /ws/phone (mTLS WebSocket from Android APK)
  L3 Gateway            -> middleware on L2 (auth, rate-limit, dedup)
  L4 Pi Engine          -> orchestrator, runs the PW skill
  L5 Skill Execution    -> tank.app.skill.* modules
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, PlainTextResponse

from .config import settings
from .crypto.signing import public_key_b64
from .db import conn, init_db
from .models import FlowEvent
from .openclaw.gateway import Gateway
from .openclaw.pi_engine import PiEngine
from .openclaw.whatsapp import parse_button_reply, send_alert

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("warden")

OPENCLAW_DIR = Path(__file__).resolve().parent.parent.parent / "openclaw"
TRACKER_DB = Path(__file__).resolve().parent.parent / "data" / "trackers.txt"


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    app.state.gateway = Gateway()
    await app.state.gateway.connect()
    app.state.engine = PiEngine(
        tracker_db=TRACKER_DB,
        soul_path=OPENCLAW_DIR / "SOUL.md",
    )
    app.state.connected_phones = {}  # device_id -> WebSocket
    log.info(
        "Tank ready. tracker_db=%d entries. public_key=%s",
        app.state.engine.matcher.size,
        public_key_b64(),
    )
    yield


app = FastAPI(title="Privacy Warden Tank", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── L0 health & meta ───────────────────────────────────────────────────────────


@app.get("/healthz")
async def health() -> dict:
    return {"ok": True, "ts": time.time()}


@app.get("/meta")
async def meta() -> dict:
    return {
        "service": "privacy-warden-tank",
        "version": app.version,
        "public_key": public_key_b64(),
        "tracker_db_size": app.state.engine.matcher.size,
    }


# ── L2/L3/L4 phone WebSocket ───────────────────────────────────────────────────


@app.websocket("/ws/phone")
async def ws_phone(ws: WebSocket) -> None:
    await ws.accept()
    device_id = ws.query_params.get("device_id", "unknown")
    app.state.connected_phones[device_id] = ws
    log.info("phone connected device_id=%s", device_id)
    try:
        while True:
            raw = await ws.receive_text()
            try:
                event = FlowEvent.model_validate_json(raw)
            except Exception as e:
                await ws.send_json({"error": f"bad event: {e}"})
                continue

            # L3 gateway: dedup + rate-limit
            gw: Gateway = app.state.gateway
            if not await gw.rate_limit(event.device_id):
                continue
            key = gw.event_hash(event.device_id, event.sni, int(event.ts.timestamp() // 60))
            if await gw.is_duplicate(key):
                continue

            # persist
            with conn() as c:
                c.execute(
                    "INSERT INTO flow_events(device_id, app_package, app_label, sni, dst_ip, dst_port, bytes_up, bytes_down, ja3) VALUES(?,?,?,?,?,?,?,?,?)",
                    (
                        event.device_id, event.app_package, event.app_label, event.sni,
                        event.dst_ip, event.dst_port, event.bytes_up, event.bytes_down, event.ja3,
                    ),
                )

            # L4 pi engine
            alert, signed_rule = app.state.engine.process(event)
            if alert is None:
                continue

            with conn() as c:
                tracker_json = (
                    json.dumps(alert.tracker.model_dump()) if alert.tracker else None
                )
                c.execute(
                    "INSERT OR REPLACE INTO alerts(id, device_id, app_label, domain, severity, explanation, suggested_action, tracker_json) VALUES(?,?,?,?,?,?,?,?)",
                    (
                        alert.id, alert.device_id, alert.app_label, alert.domain,
                        alert.severity, alert.explanation, alert.suggested_action, tracker_json,
                    ),
                )

            # L1 communication: notify the user
            asyncio.create_task(send_alert(alert))

            # push signed rule (if any) to the phone
            if signed_rule is not None:
                await ws.send_json({"type": "rule", "data": signed_rule.model_dump(mode="json")})
                with conn() as c:
                    r = signed_rule.rule
                    c.execute(
                        "INSERT OR REPLACE INTO rules(id, device_id, app_package, domain, action) VALUES(?,?,?,?,?)",
                        (r.id, r.device_id, r.app_package, r.domain, r.action),
                    )
    except WebSocketDisconnect:
        log.info("phone disconnected device_id=%s", device_id)
    finally:
        app.state.connected_phones.pop(device_id, None)


# ── L1 WhatsApp webhook (in) ───────────────────────────────────────────────────


@app.get("/webhook/whatsapp")
async def wa_verify(request: Request):
    qp = request.query_params
    if (
        qp.get("hub.mode") == "subscribe"
        and qp.get("hub.verify_token") == settings.wa_verify_token
    ):
        return PlainTextResponse(qp.get("hub.challenge", ""))
    return JSONResponse({"error": "verify failed"}, status_code=403)


@app.post("/webhook/whatsapp")
async def wa_inbound(request: Request):
    payload = await request.json()
    # Meta payload: entry[].changes[].value.messages[]
    for entry in payload.get("entry", []):
        for change in entry.get("changes", []):
            value = change.get("value", {})
            parsed = parse_button_reply(value)
            if not parsed:
                continue
            button, alert_id = parsed
            with conn() as c:
                c.execute("UPDATE alerts SET user_decision=? WHERE id=?", (button, alert_id))
                row = c.execute(
                    "SELECT device_id, app_label, domain FROM alerts WHERE id=?",
                    (alert_id,),
                ).fetchone()
            if row is None:
                continue
            log.info("WA reply alert=%s button=%s", alert_id, button)
            # On block, push a signed rule to the relevant phone
            if button == "block":
                from uuid import uuid4
                from .crypto.signing import sign_rule
                from .models import Rule

                signed = sign_rule(
                    Rule(
                        id=str(uuid4()),
                        device_id=row["device_id"],
                        app_package=None,
                        domain=row["domain"],
                        action="BLOCK",
                    )
                )
                ws = app.state.connected_phones.get(row["device_id"])
                if ws is not None:
                    await ws.send_json({"type": "rule", "data": signed.model_dump(mode="json")})
                with conn() as c:
                    r = signed.rule
                    c.execute(
                        "INSERT OR REPLACE INTO rules(id, device_id, app_package, domain, action) VALUES(?,?,?,?,?)",
                        (r.id, r.device_id, r.app_package, r.domain, r.action),
                    )
    return {"ok": True}


# ── Dashboard REST ────────────────────────────────────────────────────────────


@app.get("/api/alerts")
async def list_alerts(limit: int = 50):
    with conn() as c:
        rows = c.execute(
            "SELECT * FROM alerts ORDER BY created_at DESC LIMIT ?", (limit,)
        ).fetchall()
    return [dict(r) for r in rows]


@app.get("/api/flows/recent")
async def recent_flows(limit: int = 100):
    with conn() as c:
        rows = c.execute(
            "SELECT * FROM flow_events ORDER BY ts DESC LIMIT ?", (limit,)
        ).fetchall()
    return [dict(r) for r in rows]


@app.get("/api/timeline")
async def timeline(hours: int = 24):
    """Counts of alerts per hour over the last N hours."""
    with conn() as c:
        rows = c.execute(
            "SELECT strftime('%Y-%m-%d %H:00', created_at) AS bucket, "
            "COUNT(*) AS n, "
            "SUM(CASE WHEN severity IN ('HIGH','CRITICAL') THEN 1 ELSE 0 END) AS critical "
            "FROM alerts WHERE created_at >= datetime('now', ?) "
            "GROUP BY bucket ORDER BY bucket",
            (f"-{hours} hours",),
        ).fetchall()
    return [dict(r) for r in rows]


@app.get("/api/severity")
async def severity_breakdown():
    with conn() as c:
        rows = c.execute(
            "SELECT severity, COUNT(*) AS n FROM alerts GROUP BY severity"
        ).fetchall()
    return [dict(r) for r in rows]


@app.get("/api/categories")
async def categories():
    with conn() as c:
        rows = c.execute(
            "SELECT json_extract(tracker_json,'$.category') AS category, COUNT(*) AS n "
            "FROM alerts WHERE tracker_json IS NOT NULL "
            "GROUP BY category ORDER BY n DESC"
        ).fetchall()
    return [dict(r) for r in rows]


@app.get("/api/stats")
async def stats():
    with conn() as c:
        total = c.execute("SELECT COUNT(*) AS n FROM flow_events").fetchone()["n"]
        alerts = c.execute("SELECT COUNT(*) AS n FROM alerts").fetchone()["n"]
        blocked = c.execute(
            "SELECT COUNT(*) AS n FROM rules WHERE action='BLOCK'"
        ).fetchone()["n"]
        per_app = c.execute(
            "SELECT app_label, COUNT(*) AS n FROM alerts GROUP BY app_label ORDER BY n DESC LIMIT 10"
        ).fetchall()
        per_company = c.execute(
            "SELECT json_extract(tracker_json,'$.company') AS company, COUNT(*) AS n "
            "FROM alerts WHERE tracker_json IS NOT NULL GROUP BY company ORDER BY n DESC LIMIT 10"
        ).fetchall()
    return {
        "flows_total": total,
        "alerts_total": alerts,
        "rules_blocked": blocked,
        "top_apps": [dict(r) for r in per_app],
        "top_companies": [dict(r) for r in per_company],
    }


# ── Dev-only: simulate a phone event ──────────────────────────────────────────


@app.post("/api/_sim/event")
async def simulate(event: FlowEvent):
    """Convenience endpoint for testing without an Android device."""
    gw: Gateway = app.state.gateway
    key = gw.event_hash(event.device_id, event.sni, int(event.ts.timestamp() // 60))
    if await gw.is_duplicate(key):
        return {"deduped": True}
    with conn() as c:
        c.execute(
            "INSERT INTO flow_events(device_id, app_package, app_label, sni, bytes_up, bytes_down) VALUES(?,?,?,?,?,?)",
            (event.device_id, event.app_package, event.app_label, event.sni, event.bytes_up, event.bytes_down),
        )
    alert, signed = app.state.engine.process(event)
    if alert:
        with conn() as c:
            tj = json.dumps(alert.tracker.model_dump()) if alert.tracker else None
            c.execute(
                "INSERT OR REPLACE INTO alerts(id, device_id, app_label, domain, severity, explanation, suggested_action, tracker_json) VALUES(?,?,?,?,?,?,?,?)",
                (alert.id, alert.device_id, alert.app_label, alert.domain,
                 alert.severity, alert.explanation, alert.suggested_action, tj),
            )
        asyncio.create_task(send_alert(alert))
    if signed is not None:
        with conn() as c:
            r = signed.rule
            c.execute(
                "INSERT OR REPLACE INTO rules(id, device_id, app_package, domain, action) VALUES(?,?,?,?,?)",
                (r.id, r.device_id, r.app_package, r.domain, r.action),
            )
    return {
        "alert": alert.model_dump(mode="json") if alert else None,
        "signed_rule": signed.model_dump(mode="json") if signed else None,
    }
