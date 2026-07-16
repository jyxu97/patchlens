"""POST /webhooks/github

Validates the HMAC-SHA256 signature, deduplicates via DB unique constraint,
and enqueues a job to RabbitMQ.
"""

from __future__ import annotations

import hashlib
import hmac
import logging

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.schemas.webhook import WebhookPayload
from app.services import review_job as review_job_service
from app.worker.consumer import publish_job

router = APIRouter(prefix="/webhooks", tags=["webhooks"])
log = logging.getLogger(__name__)

HANDLED_ACTIONS = {"opened", "synchronize", "reopened"}


def _verify_signature(body: bytes, signature_header: str | None) -> None:
    if not settings.github_webhook_secret:
        return  # skip in dev/test when secret is not configured
    if not signature_header or not signature_header.startswith("sha256="):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing signature")
    expected = "sha256=" + hmac.new(
        settings.github_webhook_secret.encode(), body, hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(expected, signature_header):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid signature")


@router.post("/github", status_code=status.HTTP_202_ACCEPTED)
async def github_webhook(
    request: Request,
    x_hub_signature_256: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
) -> dict:
    body = await request.body()
    _verify_signature(body, x_hub_signature_256)

    payload = WebhookPayload.model_validate_json(body)

    if payload.action not in HANDLED_ACTIONS:
        return {"status": "ignored", "reason": f"action '{payload.action}' not handled"}

    pr = payload.pull_request
    repo = payload.repository

    job = await review_job_service.create_or_find(
        db,
        owner=repo.owner.login,
        repo=repo.name,
        pull_number=pr.number,
        pr_url=pr.html_url,
        head_sha=pr.head.sha,
    )

    if job.status == "PENDING":
        await publish_job(
            job_id=job.id,
            owner=repo.owner.login,
            repo=repo.name,
            pull_number=pr.number,
        )
        log.info("Enqueued job %d for PR #%d %s/%s", job.id, pr.number, repo.owner.login, repo.name)

    return {"status": "accepted", "job_id": job.id}
