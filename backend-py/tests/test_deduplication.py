"""Deduplication stress tests.

Verifies that 300 sequential + 250 concurrent duplicate webhook events each
produce exactly ONE ReviewJob row, exercising the ON CONFLICT DO NOTHING logic.
"""

import asyncio

import pytest
import pytest_asyncio
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.review_job import ReviewJob

PR_KWARGS = dict(
    owner="octocat",
    repo="hello-world",
    pull_number=42,
    pr_url="https://github.com/octocat/hello-world/pull/42",
    head_sha="abc123def456abc123def456abc123def456abc1",
)


async def _count(session_factory) -> int:
    async with session_factory() as session:
        result = await session.scalar(select(func.count()).select_from(ReviewJob))
        return result or 0


@pytest.mark.asyncio
async def test_sequential_300_duplicate_webhooks_creates_single_job(session_factory, service):
    for _ in range(300):
        job = await service(**PR_KWARGS)
        assert job is not None

    count = await _count(session_factory)
    assert count == 1, f"Expected 1 job, got {count}"


@pytest.mark.asyncio
async def test_concurrent_250_duplicate_webhooks_creates_single_job(session_factory, service):
    tasks = [service(**PR_KWARGS) for _ in range(250)]
    results = await asyncio.gather(*tasks)

    unique_ids = {r.id for r in results}
    assert len(unique_ids) == 1, f"Expected 1 unique job id, got {len(unique_ids)}: {unique_ids}"

    count = await _count(session_factory)
    assert count == 1, f"Expected 1 job, got {count}"
