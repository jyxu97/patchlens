"""Redis caching for PR analysis results.

Cache key: patchlens:review:{owner}:{repo}:{pull_number}:{diff_hash}

A cache hit returns the previously computed ReviewResult + GroundingReport,
skipping the expensive RAG retrieval and OpenAI API call entirely.

Redis failures are silently swallowed so a Redis outage never blocks a review.
"""

from __future__ import annotations

import json
import logging

from redis.asyncio import Redis

from app.config import settings

log = logging.getLogger(__name__)

_redis: Redis | None = None


def get_redis() -> Redis:
    global _redis
    if _redis is None:
        _redis = Redis.from_url(settings.redis_url, decode_responses=True)
    return _redis


def review_key(owner: str, repo: str, pull_number: int, diff_hash: str) -> str:
    return f"patchlens:review:{owner}:{repo}:{pull_number}:{diff_hash}"


async def get(key: str) -> dict | None:
    """Return cached payload dict, or None on miss or Redis error."""
    try:
        raw = await get_redis().get(key)
        if raw is None:
            return None
        return json.loads(raw)
    except Exception as exc:
        log.warning("Redis GET failed (key=%s): %s", key, exc)
        return None


async def put(key: str, value: dict, ttl: int) -> None:
    """Store payload as JSON with TTL seconds. Silently ignores Redis errors."""
    try:
        await get_redis().set(key, json.dumps(value), ex=ttl)
    except Exception as exc:
        log.warning("Redis SET failed (key=%s): %s", key, exc)


async def close() -> None:
    """Close the connection pool (called on app shutdown)."""
    global _redis
    if _redis is not None:
        await _redis.aclose()
        _redis = None
