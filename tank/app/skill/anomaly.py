"""Volumetric anomaly detection.

Computes a rolling Z-score of connections-per-minute per (device, app, sni).
A high Z-score indicates a sudden spike worth surfacing.
"""
from __future__ import annotations

import math
import time
from collections import defaultdict, deque
from dataclasses import dataclass


WINDOW_SECONDS = 3600  # one hour rolling window


@dataclass
class AnomalyResult:
    z_score: float
    count_in_window: int
    is_anomalous: bool


class AnomalyDetector:
    def __init__(self, z_threshold: float = 3.0) -> None:
        self.z_threshold = z_threshold
        self._events: dict[tuple[str, str, str], deque[float]] = defaultdict(deque)

    def observe(self, device_id: str, app_package: str, sni: str) -> AnomalyResult:
        key = (device_id, app_package, sni)
        now = time.time()
        q = self._events[key]
        q.append(now)
        cutoff = now - WINDOW_SECONDS
        while q and q[0] < cutoff:
            q.popleft()

        # bucket per minute to compute mean/std
        buckets: dict[int, int] = defaultdict(int)
        for ts in q:
            buckets[int(ts // 60)] += 1
        if len(buckets) < 5:
            return AnomalyResult(0.0, len(q), False)

        counts = list(buckets.values())
        mean = sum(counts) / len(counts)
        var = sum((x - mean) ** 2 for x in counts) / len(counts)
        std = math.sqrt(var) or 1.0
        latest = counts[-1]
        z = (latest - mean) / std
        return AnomalyResult(z, len(q), z >= self.z_threshold)
