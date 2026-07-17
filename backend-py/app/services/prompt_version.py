"""Idempotent prompt version management.

On every app startup (called from lifespan), ensures a PromptVersion row exists
for the current (version_tag, model_name) pair.  Uses INSERT ... ON CONFLICT DO
NOTHING — safe under concurrent starts.

Each AnalysisRun stores the returned id so analysts can filter by prompt version
when computing grounding rates or latency regressions across deployments.
"""

from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.prompt_version import PromptVersion

log = logging.getLogger(__name__)


async def get_or_create(session: AsyncSession) -> PromptVersion:
    """Return the PromptVersion row for the current config tag, creating it if absent."""
    stmt = (
        sqlite_insert(PromptVersion)
        .values(
            version_tag=settings.prompt_version_tag,
            model_name=settings.openai_model,
            notes=settings.prompt_version_notes,
        )
        .on_conflict_do_nothing(index_elements=["version_tag"])
        .returning(PromptVersion.id)
    )
    result = await session.execute(stmt)
    await session.commit()
    row = result.fetchone()

    if row:
        version = await session.get(PromptVersion, row[0])
        log.info(
            "Registered prompt version '%s' (model=%s, id=%d)",
            settings.prompt_version_tag, settings.openai_model, version.id,
        )
        return version

    # Row already existed — fetch it
    existing = await session.scalar(
        select(PromptVersion).where(PromptVersion.version_tag == settings.prompt_version_tag)
    )
    log.info("Using existing prompt version '%s' (id=%d)", settings.prompt_version_tag, existing.id)
    return existing
