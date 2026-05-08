"""LLM-powered severity classifier and explanation generator.

Hackathon v1: deterministic templates based on tracker category + frequency.
v2: swap in Phi-3.5 Mini via llama.cpp using the same `explain()` signature.
"""
from __future__ import annotations

from ..models import Action, FlowEvent, Severity, TrackerHit


CATEGORY_DATA_HINTS = {
    "advertising": "ad-targeting profile (interests, behaviour, location)",
    "analytics": "in-app behaviour (screens viewed, taps, session times)",
    "attribution": "install attribution (which ad you clicked, device IDs)",
    "engagement": "push-notification profile + in-app event stream",
    "crash-reporting": "stack traces (low-risk, mostly diagnostic)",
    "session-replay": "screen recordings of how you use the app",
    "identification": "stable device identifiers used to track you across apps",
    "feature-flags": "feature-rollout cohort assignments (low-risk)",
}


def classify(tracker: TrackerHit | None, anomaly_z: float, count_in_window: int) -> Severity:
    if tracker is None and anomaly_z < 3:
        return "LOW"
    base: Severity = "LOW"
    if tracker:
        base = {
            "advertising": "HIGH",
            "attribution": "HIGH",
            "session-replay": "CRITICAL",
            "identification": "HIGH",
            "engagement": "MEDIUM",
            "analytics": "MEDIUM",
            "crash-reporting": "LOW",
            "feature-flags": "LOW",
        }.get(tracker.category, "MEDIUM")  # type: ignore[assignment]
    if anomaly_z >= 5 or count_in_window > 100:
        return "CRITICAL" if base == "HIGH" else "HIGH"
    return base


def suggest_action(severity: Severity, tracker: TrackerHit | None) -> Action:
    if severity in ("HIGH", "CRITICAL"):
        return "BLOCK"
    if tracker and tracker.category == "crash-reporting":
        return "ALLOW"
    return "WATCH"


def explain(
    event: FlowEvent,
    tracker: TrackerHit | None,
    anomaly_z: float,
    count_in_window: int,
) -> str:
    app = event.app_label or event.app_package
    if tracker is None:
        return (
            f"{app} contacted {event.sni} {count_in_window} times recently — "
            f"this destination is not in our known-tracker database, but the "
            f"frequency (z={anomaly_z:.1f}) is unusual."
        )
    leak = CATEGORY_DATA_HINTS.get(tracker.category, "user data")
    company = tracker.company or tracker.tracker_name
    return (
        f"{app} is sending data to {company} ({tracker.tracker_name}). "
        f"This category typically exfiltrates: {leak}. "
        f"Seen {count_in_window} times in the last hour."
    )
