"""Ed25519 rule signing using PyNaCl."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path

from nacl.signing import SigningKey, VerifyKey

from ..config import settings
from ..models import Rule, SignedRule


_signing_key: SigningKey | None = None


def _load_or_generate() -> SigningKey:
    priv = Path(settings.tank_private_key_path)
    pub = Path(settings.tank_public_key_path)
    priv.parent.mkdir(parents=True, exist_ok=True)
    if priv.exists():
        return SigningKey(priv.read_bytes())
    sk = SigningKey.generate()
    priv.write_bytes(bytes(sk))
    pub.write_bytes(bytes(sk.verify_key))
    os.chmod(priv, 0o600)
    return sk


def get_signing_key() -> SigningKey:
    global _signing_key
    if _signing_key is None:
        _signing_key = _load_or_generate()
    return _signing_key


def public_key_b64() -> str:
    return base64.b64encode(bytes(get_signing_key().verify_key)).decode()


def sign_rule(rule: Rule) -> SignedRule:
    sk = get_signing_key()
    payload_str = rule.model_dump_json(by_alias=True)
    sig = sk.sign(payload_str.encode()).signature
    return SignedRule(
        rule=rule,
        signature=base64.b64encode(sig).decode(),
        public_key=public_key_b64(),
        signed_payload=payload_str,
    )


def verify(message: bytes, signature_b64: str, public_key_b64_str: str) -> bool:
    try:
        vk = VerifyKey(base64.b64decode(public_key_b64_str))
        vk.verify(message, base64.b64decode(signature_b64))
        return True
    except Exception:
        return False
