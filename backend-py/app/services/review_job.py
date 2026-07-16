import json

from sqlalchemy import select
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.review_job import ReviewJob


async def create_or_find(
    session: AsyncSession,
    *,
    owner: str,
    repo: str,
    pull_number: int,
    pr_url: str,
    head_sha: str,
) -> ReviewJob:
    """Insert a new ReviewJob or return the existing one if the (owner, repo, pull_number,
    head_sha) tuple already exists.  Uses INSERT ... ON CONFLICT DO NOTHING so concurrent
    callers all converge to the single canonical row without locking.

    Uses SQLite dialect insert for compatibility in tests (SQLite) and prod (PostgreSQL)
    since both support ON CONFLICT DO NOTHING on unique columns.
    """
    stmt = (
        sqlite_insert(ReviewJob)
        .values(
            owner=owner,
            repo=repo,
            pull_number=pull_number,
            pr_url=pr_url,
            head_sha=head_sha,
            status="PENDING",
        )
        .on_conflict_do_nothing(index_elements=["owner", "repo", "pull_number", "head_sha"])
        .returning(ReviewJob.id)
    )
    result = await session.execute(stmt)
    await session.commit()
    row = result.fetchone()

    if row:
        job = await session.get(ReviewJob, row[0])
        return job

    # Another concurrent INSERT won the race – re-query the existing row.
    existing = await session.scalar(
        select(ReviewJob).where(
            ReviewJob.owner == owner,
            ReviewJob.repo == repo,
            ReviewJob.pull_number == pull_number,
            ReviewJob.head_sha == head_sha,
        )
    )
    return existing


async def update_status(
    session: AsyncSession,
    job_id: int,
    status: str,
    result: dict | None = None,
    error_message: str | None = None,
) -> None:
    job = await session.get(ReviewJob, job_id)
    if job is None:
        return
    job.status = status
    if result is not None:
        job.result = json.dumps(result)
    if error_message is not None:
        job.error_message = error_message
    await session.commit()
