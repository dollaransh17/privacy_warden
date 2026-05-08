"""Policy engine: reads SOUL.md (user's living privacy policy) and decides
whether to auto-block, prompt, or allow.

SOUL.md is a tiny YAML-front-matter markdown file. We parse only the front
matter for machine rules; the markdown body is human-readable.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from ..models import Action, Severity


@dataclass
class Soul:
    auto_block_categories: list[str] = field(default_factory=list)
    auto_allow_categories: list[str] = field(default_factory=list)
    auto_block_severity: Severity = "CRITICAL"  # auto-act at or above this
    pinned_blocks: list[str] = field(default_factory=list)  # domains
    pinned_allows: list[str] = field(default_factory=list)


_FRONT_MATTER = re.compile(r"^---\s*\n(.*?)\n---\s*\n?", re.DOTALL)


def load_soul(path: str | Path) -> Soul:
    p = Path(path)
    if not p.exists():
        return Soul(
            auto_block_categories=["session-replay"],
            auto_block_severity="CRITICAL",
        )
    text = p.read_text(encoding="utf-8")
    m = _FRONT_MATTER.match(text)
    if not m:
        return Soul()
    body = m.group(1)
    soul = Soul()
    current_list: list[str] | None = None
    for raw in body.splitlines():
        line = raw.rstrip()
        if not line:
            continue
        if line.startswith("- ") and current_list is not None:
            current_list.append(line[2:].strip())
            continue
        if ":" in line:
            key, _, val = line.partition(":")
            key, val = key.strip(), val.strip()
            current_list = None
            if key == "auto_block_severity" and val:
                soul.auto_block_severity = val.upper()  # type: ignore[assignment]
            elif key == "auto_block_categories":
                current_list = soul.auto_block_categories
            elif key == "auto_allow_categories":
                current_list = soul.auto_allow_categories
            elif key == "pinned_blocks":
                current_list = soul.pinned_blocks
            elif key == "pinned_allows":
                current_list = soul.pinned_allows
    return soul


_SEV_ORDER: dict[Severity, int] = {"LOW": 0, "MEDIUM": 1, "HIGH": 2, "CRITICAL": 3}


def decide(
    soul: Soul,
    domain: str,
    category: str | None,
    severity: Severity,
    suggested: Action,
) -> tuple[Action, bool]:
    """Returns (final_action, requires_user_consent)."""
    if domain in soul.pinned_blocks:
        return ("BLOCK", False)
    if domain in soul.pinned_allows:
        return ("ALLOW", False)
    if category and category in soul.auto_allow_categories:
        return ("ALLOW", False)
    if category and category in soul.auto_block_categories:
        return ("BLOCK", False)
    if _SEV_ORDER[severity] >= _SEV_ORDER[soul.auto_block_severity]:
        return ("BLOCK", False)
    # otherwise, ask the user
    return (suggested, True)
