"""Tracker domain matcher.

Hackathon v1: Python dict + suffix matching (handles subdomains).
v2: swap to Rust aho-corasick via PyO3 — same interface.
"""
from __future__ import annotations

from pathlib import Path

from ..models import TrackerHit


class TrackerMatcher:
    def __init__(self, db_path: str | Path) -> None:
        self._exact: dict[str, TrackerHit] = {}
        self._suffixes: list[tuple[str, TrackerHit]] = []
        self._load(Path(db_path))

    def _load(self, path: Path) -> None:
        if not path.exists():
            return
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 4:
                continue
            domain, tid, name, category = parts[0], parts[1], parts[2], parts[3]
            company = parts[4] if len(parts) > 4 else None
            hit = TrackerHit(
                domain=domain,
                tracker_id=tid,
                tracker_name=name,
                category=category,
                company=company,
            )
            self._exact[domain.lower()] = hit
            # also enable suffix match: ".graph.facebook.com" matches "x.graph.facebook.com"
            self._suffixes.append(("." + domain.lower(), hit))

    def match(self, domain: str) -> TrackerHit | None:
        if not domain:
            return None
        d = domain.lower().rstrip(".")
        if d in self._exact:
            return self._exact[d]
        for suffix, hit in self._suffixes:
            if d.endswith(suffix):
                return hit
        return None

    @property
    def size(self) -> int:
        return len(self._exact)
