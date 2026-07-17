from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import Boolean, DateTime, Float, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class AnalysisRun(Base):
    """Per-analysis observability record.

    Written once at the end of a successful pipeline run. Enables:
    - Cache hit rate tracking
    - Per-stage latency breakdown (GitHub / RAG / LLM / total)
    - Token usage monitoring
    - Grounding (hallucination) rate trending
    """

    __tablename__ = "analysis_runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)

    # Identity
    owner: Mapped[str] = mapped_column(String(255), nullable=False)
    repo: Mapped[str] = mapped_column(String(255), nullable=False)
    pull_number: Mapped[int] = mapped_column(Integer, nullable=False)
    pr_url: Mapped[str] = mapped_column(String(1024), nullable=False)
    diff_hash: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)

    # Cache
    cache_hit: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    # Latencies (milliseconds)
    github_latency_ms: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    retrieval_latency_ms: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    llm_latency_ms: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    total_latency_ms: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)

    # Token usage
    prompt_tokens: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    completion_tokens: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    model_name: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)

    # Grounding
    hallucinated_ref_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    grounding_rate: Mapped[float] = mapped_column(Float, nullable=False, default=1.0)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )
