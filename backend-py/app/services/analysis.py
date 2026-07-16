"""End-to-end analysis pipeline: GitHub → diff → risk → RAG → OpenAI → grounding → persist."""

from __future__ import annotations

import logging

from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.review import GroundingReport, ReviewResult
from app.services import review_job as review_job_service
from app.services.context import retrieve_context
from app.services.diff_parser import parse_diff
from app.services.github import fetch_pr_metadata
from app.services.grounding import validate as grounding_validate
from app.services.openai_service import analyze
from app.services.risk_scoring import score_pr

log = logging.getLogger(__name__)


async def run_analysis(
    session: AsyncSession,
    job_id: int,
    owner: str,
    repo: str,
    pull_number: int,
) -> None:
    try:
        await review_job_service.update_status(session, job_id, "IN_PROGRESS")

        # 1. Fetch PR metadata + diff from GitHub
        pr = await fetch_pr_metadata(owner, repo, pull_number)

        # 2. Parse diff into per-file objects
        file_diffs = parse_diff(pr.diff)

        # 3. Rule-based risk scoring
        risk_score, risk_factors = score_pr(file_diffs)

        # 4. pgvector RAG context retrieval
        rag_snippets = await retrieve_context(session, pr.diff[:2000])

        # 5. OpenAI call + Pydantic v2 structural validation
        result: ReviewResult = await analyze(
            diff=pr.diff,
            risk_score=risk_score,
            risk_factors=risk_factors,
            rag_context=rag_snippets,
        )

        # 6. Grounding validation — detect hallucinated file paths
        grounding: GroundingReport = grounding_validate(
            risky_files=result.risk_assessment.risky_files,
            changed_files=pr.changed_files,
        )
        if grounding.hallucinated_count > 0:
            log.warning(
                "Job %d: LLM hallucinated %d/%d file paths: %s",
                job_id,
                grounding.hallucinated_count,
                grounding.total_risky_files,
                grounding.hallucinated_paths,
            )

        await review_job_service.update_status(
            session,
            job_id,
            "COMPLETED",
            result=result.model_dump(),
            grounding_report=grounding.model_dump(),
        )
        log.info(
            "Job %d completed (PR #%d %s/%s) grounding_rate=%.2f",
            job_id, pull_number, owner, repo, grounding.grounding_rate,
        )

    except Exception as exc:
        log.exception("Job %d failed", job_id)
        await review_job_service.update_status(
            session, job_id, "FAILED", error_message=str(exc)
        )
        raise
