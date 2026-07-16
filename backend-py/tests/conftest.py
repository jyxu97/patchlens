"""Shared pytest fixtures.

Uses an in-memory SQLite database (via aiosqlite) so the deduplication tests
run without any external services.
"""

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# Import models so metadata is populated before create_all
from app.database import Base
from app.models import review_job  # noqa: F401
from app.services import review_job as review_job_service

TEST_DB_URL = "sqlite+aiosqlite:///:memory:"


@pytest_asyncio.fixture
async def engine():
    eng = create_async_engine(TEST_DB_URL, echo=False)
    async with eng.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield eng
    await eng.dispose()


@pytest_asyncio.fixture
async def session_factory(engine):
    return async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


@pytest_asyncio.fixture
def service(session_factory):
    """Return a thin callable that wraps create_or_find with a fresh session."""

    async def _create(**kwargs):
        async with session_factory() as session:
            return await review_job_service.create_or_find(session, **kwargs)

    return _create
