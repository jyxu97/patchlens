import json

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.review_job import ReviewJob
from app.schemas.review import GroundingReport, JobResponse, ReviewResult

router = APIRouter(prefix="/jobs", tags=["jobs"])


@router.get("/{job_id}", response_model=JobResponse)
async def get_job(job_id: int, db: AsyncSession = Depends(get_db)) -> JobResponse:
    job: ReviewJob | None = await db.get(ReviewJob, job_id)
    if job is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Job not found")

    result = None
    if job.result:
        result = ReviewResult.model_validate(json.loads(job.result))

    grounding_report = None
    if job.grounding_report:
        grounding_report = GroundingReport.model_validate(json.loads(job.grounding_report))

    return JobResponse(
        id=job.id,
        owner=job.owner,
        repo=job.repo,
        pull_number=job.pull_number,
        pr_url=job.pr_url,
        head_sha=job.head_sha,
        status=job.status,
        result=result,
        grounding_report=grounding_report,
        error_message=job.error_message,
    )
