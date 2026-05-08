"""L4 — Pi Engine: orchestrates skill modules to turn a flow event into a decision."""
from __future__ import annotations

import uuid
from datetime import datetime
from pathlib import Path

from ..config import settings
from ..models import Alert, FlowEvent, Rule, Severity, SignedRule
from ..crypto.signing import sign_rule
from ..skill.anomaly import AnomalyDetector
from ..skill.explainer import classify, explain, suggest_action
from ..skill.policy import Soul, decide, load_soul
from ..skill.tracker_matcher import TrackerMatcher


class PiEngine:
    def __init__(self, tracker_db: Path, soul_path: Path) -> None:
        self.matcher = TrackerMatcher(tracker_db)
        self.anomaly = AnomalyDetector()
        self.soul_path = soul_path
        self.soul: Soul = load_soul(soul_path)

    def reload_soul(self) -> None:
        self.soul = load_soul(self.soul_path)

    def process(self, event: FlowEvent) -> tuple[Alert | None, SignedRule | None]:
        tracker = self.matcher.match(event.sni)
        anom = self.anomaly.observe(event.device_id, event.app_package, event.sni)

        # Skip if not interesting
        if tracker is None and not anom.is_anomalous:
            return None, None

        severity: Severity = classify(tracker, anom.z_score, anom.count_in_window)
        suggested = suggest_action(severity, tracker)
        category = tracker.category if tracker else None
        action, needs_consent = decide(self.soul, event.sni, category, severity, suggested)

        alert = Alert(
            id=str(uuid.uuid4()),
            device_id=event.device_id,
            app_label=event.app_label or event.app_package,
            domain=event.sni,
            severity=severity,
            explanation=explain(event, tracker, anom.z_score, anom.count_in_window),
            suggested_action=suggested,
            tracker=tracker,
            created_at=datetime.utcnow(),
        )

        signed: SignedRule | None = None
        if not needs_consent and action == "BLOCK":
            rule = Rule(
                id=str(uuid.uuid4()),
                device_id=event.device_id,
                app_package=event.app_package,
                domain=event.sni,
                action="BLOCK",
            )
            signed = sign_rule(rule)

        return alert, signed
