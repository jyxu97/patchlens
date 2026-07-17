"""Tests for prompt version idempotent get_or_create."""

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.database import Base
from app.models import prompt_version as pv_model  # noqa: F401 – register model
from app.services.prompt_version import get_or_create

TEST_DB_URL = "sqlite+aiosqlite:///:memory:"


@pytest_asyncio.fixture
async def session():
    engine = create_async_engine(TEST_DB_URL, echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as s:
        yield s
    await engine.dispose()


@pytest.mark.asyncio
async def test_get_or_create_returns_version(session):
    pv = await get_or_create(session)
    assert pv.id is not None
    assert pv.version_tag == "v1.0.0"
    assert pv.model_name is not None


@pytest.mark.asyncio
async def test_get_or_create_is_idempotent(session):
    """Calling get_or_create twice must return the same row (same id)."""
    pv1 = await get_or_create(session)
    pv2 = await get_or_create(session)
    assert pv1.id == pv2.id


@pytest.mark.asyncio
async def test_get_or_create_stores_notes(session):
    pv = await get_or_create(session)
    assert pv.notes is not None
    assert len(pv.notes) > 0
