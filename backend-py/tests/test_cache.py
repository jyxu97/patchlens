"""Tests for Redis caching (cache hit/miss/graceful degradation).

Uses fakeredis so no real Redis server is needed.
"""

import pytest
import pytest_asyncio
from fakeredis.aioredis import FakeRedis

import app.services.cache as cache_module
from app.services.cache import get, put, review_key


@pytest_asyncio.fixture(autouse=True)
async def fake_redis():
    """Swap the module-level Redis client for a FakeRedis instance per test."""
    fake = FakeRedis(decode_responses=True)
    cache_module._redis = fake
    yield fake
    await fake.aclose()
    cache_module._redis = None


def test_review_key_format():
    key = review_key("octocat", "hello-world", 42, "abc123")
    assert key == "patchlens:review:octocat:hello-world:42:abc123"


@pytest.mark.asyncio
async def test_cache_miss_returns_none():
    result = await get("patchlens:review:owner:repo:1:deadbeef")
    assert result is None


@pytest.mark.asyncio
async def test_put_then_get_returns_payload():
    payload = {"result": {"summary": "ok"}, "grounding_report": {"rate": 1.0}}
    key = review_key("owner", "repo", 1, "hash1")

    await put(key, payload, ttl=3600)
    cached = await get(key)

    assert cached == payload


@pytest.mark.asyncio
async def test_ttl_is_set(fake_redis):
    key = review_key("owner", "repo", 2, "hash2")
    await put(key, {"x": 1}, ttl=3600)
    ttl = await fake_redis.ttl(key)
    assert 3590 <= ttl <= 3600


@pytest.mark.asyncio
async def test_redis_error_degrades_gracefully():
    """A broken Redis client must not propagate exceptions."""
    cache_module._redis = None  # force get_redis() to create a client that will fail
    # Patch get_redis to return a broken client
    from unittest.mock import AsyncMock, MagicMock

    broken = MagicMock()
    broken.get = AsyncMock(side_effect=ConnectionError("redis down"))
    broken.setex = AsyncMock(side_effect=ConnectionError("redis down"))
    cache_module._redis = broken

    result = await get("any-key")
    assert result is None  # should not raise

    await put("any-key", {"x": 1}, ttl=60)  # should not raise
