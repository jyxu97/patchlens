"""End-to-end analysis pipeline: GitHub → diff → risk → RAG → OpenAI → grounding → persist."""

from __future__ import annotations

import logging
import time

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.analysis_run import AnalysisRun
from app.schemas.review import GroundingReport, ReviewResult
from app.services import review_job as review_job_service
from app.services.context import retrieve_context
from app.services.diff_parser import overall_hash, parse_diff
from app.services.github import fetch_pr_metadata
from app.services.grounding import validate as grounding_validate
from app.services.openai_service import analyze
from app.services.risk_scoring import score_pr

log = logging.getLogger(__name__)


def _ms(start: float) -> int:
    return int((time.monotonic() - start) * 1000)


async def run_analysis(
    session: AsyncSession,
    job_id: int,
    owner: str,
    repo: str,
    pull_number: int,
) -> None:
    total_start = time.monotonic()
    try:
        await review_job_service.update_status(session, job_id, "IN_PROGRESS")

        # 1. Fetch PR metadata + diff from GitHub
        t0 = time.monotonic()
        pr = await fetch_pr_metadata(owner, repo, pull_number)
        github_latency_ms = _ms(t0)

        # 2. Parse diff + compute content hash
        file_diffs = parse_diff(pr.diff)
        diff_hash = overall_hash(file_diffs)

        # 3. Rule-based risk scoring
        risk_score, risk_factors = score_pr(file_diffs)

        # 4. pgvector RAG context retrieval
        t0 = time.monotonic()
        rag_snippets = await retrieve_context(session, pr.diff[:2000])
        retrieval_latency_ms = _ms(t0)

        # 5. OpenAI call + Pydantic v2 structural validation
        t0 = time.monotonic()
        analyze_result = await analyze(
            diff=pr.diff,
            risk_score=risk_score,
            risk_factors=risk_factors,
            rag_context=rag_snippets,
        )
        llm_latency_ms = _ms(t0)
        result: ReviewResult = analyze_result.review

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

        total_latency_ms = _ms(total_start)

        # 7. Persist job result
        await review_job_service.update_status(
            session,
            job_id,
            "COMPLETED",
            result=result.model_dump(),
            grounding_report=grounding.model_dump(),
        )

        # 8. Write observability record
        run = AnalysisRun(
            owner=owner,
            repo=repo,
            pull_number=pull_number,
            pr_url=pr.diff[:0] or f"https://github.com/{owner}/{repo}/pull/{pull_number}",
            diff_hash=diff_hash,
            cache_hit=False,
            github_latency_ms=github_latency_ms,
            retrieval_latency_ms=retrieval_latency_ms,
            llm_latency_ms=llm_latency_ms,
            total_latency_ms=total_latency_ms,
            prompt_tokens=analyze_result.prompt_tokens,
            completion_tokens=analyze_result.completion_tokens,
            model_name=analyze_result.model_name,
            hallucinated_ref_count=grounding.hallucinated_count,
            grounding_rate=grounding.grounding_rate,
        )
        session.add(run)
        await session.commit()

        log.info(
            "Job %d completed (PR #%d %s/%s) total=%dms github=%dms rag=%dms llm=%dms "
            "tokens=%d grounding_rate=%.2f",
            job_id, pull_number, owner, repo,
            total_latency_ms, github_latency_ms, retrieval_latency_ms, llm_latency_ms,
            analyze_result.prompt_tokens + analyze_result.completion_tokens,
            grounding.grounding_rate,
        )

    except Exception as exc:
        log.exception("Job %d failed", job_id)
        await review_job_service.update_status(
            session, job_id, "FAILED", error_message=str(exc)
        )
        raise
