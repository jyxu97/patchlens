"""Tests for GET /metrics endpoint."""

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.database import Base, get_db
from app.main import app
from app.models import analysis_run  # noqa: F401 – register model
from app.models.analysis_run import AnalysisRun

TEST_DB_URL = "sqlite+aiosqlite:///:memory:"


@pytest_asyncio.fixture
async def db_engine():
    engine = create_async_engine(TEST_DB_URL, echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    await engine.dispose()


@pytest_asyncio.fixture
async def db_session(db_engine):
    factory = async_sessionmaker(db_engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as session:
        yield session


@pytest_asyncio.fixture
async def client(db_session):
    """FastAPI test client with the DB dependency overridden to use SQLite."""

    async def override_get_db():
        yield db_session

    app.dependency_overrides[get_db] = override_get_db
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()


async def _insert_run(session: AsyncSession, **kwargs) -> None:
    run = AnalysisRun(**kwargs)
    session.add(run)
    await session.commit()


@pytest.mark.asyncio
async def test_metrics_empty(client):
    resp = await client.get("/metrics")
    assert resp.status_code == 200
    data = resp.json()
    assert data["total_analyses"] == 0
    assert data["cache_hit_rate"] == 0.0
    assert data["avg_latency_ms"] is None


@pytest.mark.asyncio
async def test_metrics_aggregation(client, db_session):
    # 2 cache misses, 1 cache hit
    await _insert_run(db_session, owner="o", repo="r", pull_number=1, pr_url="u",
                      cache_hit=False, total_latency_ms=1000, llm_latency_ms=800,
                      grounding_rate=1.0, hallucinated_ref_count=0)
    await _insert_run(db_session, owner="o", repo="r", pull_number=2, pr_url="u",
                      cache_hit=False, total_latency_ms=2000, llm_latency_ms=1800,
                      grounding_rate=0.5, hallucinated_ref_count=1)
    await _insert_run(db_session, owner="o", repo="r", pull_number=3, pr_url="u",
                      cache_hit=True, total_latency_ms=50, llm_latency_ms=None,
                      grounding_rate=1.0, hallucinated_ref_count=0)

    resp = await client.get("/metrics")
    assert resp.status_code == 200
    data = resp.json()

    assert data["total_analyses"] == 3
    assert data["cache_hit_rate"] == pytest.approx(1 / 3, abs=0.001)
    assert data["avg_latency_ms"] == pytest.approx((1000 + 2000 + 50) / 3, abs=1)
    assert data["avg_latency_cache_miss_ms"] == pytest.approx(1500.0, abs=1)
    assert data["avg_latency_cache_hit_ms"] == pytest.approx(50.0, abs=1)
    assert data["avg_grounding_rate"] == pytest.approx((1.0 + 0.5 + 1.0) / 3, abs=0.001)
