"""L1 — Communication: WhatsApp Cloud API send + webhook verification.

If WA_TOKEN is empty, runs in DRY mode (logs to stdout) so dev works offline.
"""
from __future__ import annotations

import logging

import httpx

from ..config import settings
from ..models import Alert

log = logging.getLogger(__name__)

GRAPH_URL = "https://graph.facebook.com/v20.0"


async def send_alert(alert: Alert) -> dict:
    body = (
        f"*{alert.severity}* — {alert.app_label} → {alert.domain}\n\n"
        f"{alert.explanation}\n\n"
        f"_Suggested: {alert.suggested_action}_"
    )

    if not settings.wa_token or not settings.wa_phone_number_id or not settings.wa_recipient:
        log.warning("[WA-DRY] %s", body.replace("\n", " | "))
        return {"dry_run": True, "alert_id": alert.id}

    payload = {
        "messaging_product": "whatsapp",
        "recipient_type": "individual",
        "to": settings.wa_recipient,
        "type": "interactive",
        "interactive": {
            "type": "button",
            "body": {"text": body},
            "action": {
                "buttons": [
                    {"type": "reply", "reply": {"id": f"block:{alert.id}", "title": "Block"}},
                    {"type": "reply", "reply": {"id": f"allow:{alert.id}", "title": "Allow"}},
                    {"type": "reply", "reply": {"id": f"details:{alert.id}", "title": "Details"}},
                ]
            },
        },
    }
    headers = {"Authorization": f"Bearer {settings.wa_token}"}
    async with httpx.AsyncClient(timeout=10) as client:
        r = await client.post(
            f"{GRAPH_URL}/{settings.wa_phone_number_id}/messages",
            json=payload,
            headers=headers,
        )
        r.raise_for_status()
        return r.json()


def parse_button_reply(value: dict) -> tuple[str, str] | None:
    """Returns (button, alert_id) or None.

    Meta webhook payload shape:
    value['messages'][0]['interactive']['button_reply']['id'] = "block:<alert_id>"
    """
    try:
        msg = value["messages"][0]
        btn_id = msg["interactive"]["button_reply"]["id"]
        button, _, alert_id = btn_id.partition(":")
        if button in ("block", "allow", "details") and alert_id:
            return button, alert_id
    except (KeyError, IndexError, TypeError):
        return None
    return None
