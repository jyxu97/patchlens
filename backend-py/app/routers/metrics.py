"""GET /metrics — aggregated observability data.

Returns:
- total_analyses: all completed pipeline runs
- cache_hit_rate: fraction served from Redis cache
- avg_latency_ms: average total pipeline latency
- avg_latency_cache_miss_ms: avg latency when cache was bypassed (real OpenAI call)
- avg_latency_cache_hit_ms: avg latency when result was served from cache
- avg_llm_latency_ms: average time spent in OpenAI call
- avg_grounding_rate: average LLM hallucination-free rate across all runs
"""

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.analysis_run import AnalysisRun

router = APIRouter(prefix="/metrics", tags=["metrics"])


class MetricsResponse(BaseModel):
    total_analyses: int
    cache_hit_rate: float
    avg_latency_ms: float | None
    avg_latency_cache_miss_ms: float | None
    avg_latency_cache_hit_ms: float | None
    avg_llm_latency_ms: float | None
    avg_grounding_rate: float | None


@router.get("", response_model=MetricsResponse)
async def get_metrics(db: AsyncSession = Depends(get_db)) -> MetricsResponse:
    total = await db.scalar(select(func.count()).select_from(AnalysisRun)) or 0
    cache_hits = await db.scalar(
        select(func.count()).where(AnalysisRun.cache_hit.is_(True))
    ) or 0

    avg_total = await db.scalar(select(func.avg(AnalysisRun.total_latency_ms)))
    avg_miss = await db.scalar(
        select(func.avg(AnalysisRun.total_latency_ms)).where(AnalysisRun.cache_hit.is_(False))
    )
    avg_hit = await db.scalar(
        select(func.avg(AnalysisRun.total_latency_ms)).where(AnalysisRun.cache_hit.is_(True))
    )
    avg_llm = await db.scalar(select(func.avg(AnalysisRun.llm_latency_ms)))
    avg_grounding = await db.scalar(select(func.avg(AnalysisRun.grounding_rate)))

    return MetricsResponse(
        total_analyses=total,
        cache_hit_rate=round(cache_hits / total, 4) if total else 0.0,
        avg_latency_ms=round(float(avg_total), 1) if avg_total is not None else None,
        avg_latency_cache_miss_ms=round(float(avg_miss), 1) if avg_miss is not None else None,
        avg_latency_cache_hit_ms=round(float(avg_hit), 1) if avg_hit is not None else None,
        avg_llm_latency_ms=round(float(avg_llm), 1) if avg_llm is not None else None,
        avg_grounding_rate=round(float(avg_grounding), 4) if avg_grounding is not None else None,
    )
