"""L3 — Gateway: auth, rate-limit, deduplication."""
from __future__ import annotations

import hashlib
import time
from collections import defaultdict, deque

import redis.asyncio as aioredis

from ..config import settings


class Gateway:
    """Best-effort: uses Redis when available, falls back to in-memory."""

    def __init__(self) -> None:
        self._redis: aioredis.Redis | None = None
        self._mem_seen: dict[str, float] = {}
        self._mem_rate: dict[str, deque[float]] = defaultdict(deque)

    async def connect(self) -> None:
        try:
            self._redis = aioredis.from_url(settings.redis_url, decode_responses=True)
            await self._redis.ping()
        except Exception:
            self._redis = None

    @staticmethod
    def event_hash(device_id: str, sni: str, ts_minute: int) -> str:
        h = hashlib.sha256(f"{device_id}|{sni}|{ts_minute}".encode()).hexdigest()
        return f"flow:{h[:16]}"

    async def is_duplicate(self, key: str, ttl: int = 60) -> bool:
        if self._redis is not None:
            return not bool(await self._redis.set(key, "1", nx=True, ex=ttl))
        # memory fallback
        now = time.time()
        # opportunistic eviction
        for k, t in list(self._mem_seen.items()):
            if t < now - ttl:
                self._mem_seen.pop(k, None)
        if key in self._mem_seen:
            return True
        self._mem_seen[key] = now
        return False

    async def rate_limit(self, device_id: str, max_per_min: int = 6000) -> bool:
        """Returns True if accepted, False if over the limit."""
        if self._redis is not None:
            bucket = f"rl:{device_id}:{int(time.time() // 60)}"
            count = await self._redis.incr(bucket)
            if count == 1:
                await self._redis.expire(bucket, 70)
            return count <= max_per_min
        q = self._mem_rate[device_id]
        now = time.time()
        q.append(now)
        while q and q[0] < now - 60:
            q.popleft()
        return len(q) <= max_per_min
