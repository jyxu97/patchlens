from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class PromptVersion(Base):
    """Tracks every distinct system-prompt version used in production.

    A row is created (idempotently) on startup via prompt_version.get_or_create().
    Each AnalysisRun holds a FK to the version that produced it, enabling
    offline evaluation: compare grounding_rate or latency across prompt iterations.
    """

    __tablename__ = "prompt_versions"
    __table_args__ = (
        UniqueConstraint("version_tag", name="uq_prompt_versions_tag"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    version_tag: Mapped[str] = mapped_column(String(100), nullable=False)
    model_name: Mapped[str] = mapped_column(String(100), nullable=False)
    notes: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )
