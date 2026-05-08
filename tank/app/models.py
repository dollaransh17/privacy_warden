"""Pydantic models shared across OpenClaw layers."""
from __future__ import annotations

from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field


Severity = Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
Action = Literal["BLOCK", "ALLOW", "WATCH"]


class FlowEvent(BaseModel):
    """A single network flow observation from the phone."""

    device_id: str
    app_package: str
    app_label: str | None = None
    sni: str
    dst_ip: str | None = None
    dst_port: int = 443
    bytes_up: int = 0
    bytes_down: int = 0
    ts: datetime = Field(default_factory=datetime.utcnow)
    ja3: str | None = None
    signature: str | None = None  # Ed25519 signature from phone (hex)


class TrackerHit(BaseModel):
    domain: str
    tracker_id: str
    tracker_name: str
    category: str
    company: str | None = None


class Alert(BaseModel):
    id: str
    device_id: str
    app_label: str
    domain: str
    severity: Severity
    explanation: str
    suggested_action: Action
    tracker: TrackerHit | None = None
    created_at: datetime = Field(default_factory=datetime.utcnow)


class Rule(BaseModel):
    """Signed rule pushed back to the phone."""

    id: str
    device_id: str
    app_package: str | None = None  # None = applies to all apps
    domain: str
    action: Action
    expires_at: datetime | None = None
    issued_at: datetime = Field(default_factory=datetime.utcnow)


class SignedRule(BaseModel):
    rule: Rule
    signature: str  # base64 Ed25519
    public_key: str  # base64 Ed25519 (for convenience)
    signed_payload: str = ""  # exact JSON bytes that were signed (for cross-stack verify)


class WAButtonReply(BaseModel):
    alert_id: str
    button: Literal["block", "allow", "details"]
    user_phone: str
