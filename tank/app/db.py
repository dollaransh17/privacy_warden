"""SQLite persistence (SQLCipher upgrade comes later via pysqlcipher3).

Schema kept intentionally small for hackathon demo.
"""
from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path

from .config import settings

SCHEMA = """
CREATE TABLE IF NOT EXISTS flow_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    app_package TEXT NOT NULL,
    app_label TEXT,
    sni TEXT NOT NULL,
    dst_ip TEXT,
    dst_port INTEGER,
    bytes_up INTEGER,
    bytes_down INTEGER,
    ja3 TEXT,
    ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_flows_device_ts ON flow_events(device_id, ts);
CREATE INDEX IF NOT EXISTS idx_flows_sni ON flow_events(sni);

CREATE TABLE IF NOT EXISTS alerts (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    app_label TEXT,
    domain TEXT,
    severity TEXT,
    explanation TEXT,
    suggested_action TEXT,
    tracker_json TEXT,
    user_decision TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rules (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    app_package TEXT,
    domain TEXT NOT NULL,
    action TEXT NOT NULL,
    issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rules_device ON rules(device_id);
"""


def _ensure_dir(path: str) -> None:
    Path(os.path.dirname(path) or ".").mkdir(parents=True, exist_ok=True)


@contextmanager
def conn():
    _ensure_dir(settings.tank_db_path)
    c = sqlite3.connect(settings.tank_db_path)
    c.row_factory = sqlite3.Row
    try:
        yield c
        c.commit()
    finally:
        c.close()


def init_db() -> None:
    with conn() as c:
        c.executescript(SCHEMA)
