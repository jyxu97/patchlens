"""FastAPI application entry point with lifespan management."""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.database import Base, get_engine
from app.routers import jobs, webhooks

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

_consumer_task: asyncio.Task | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    engine = get_engine()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    global _consumer_task
    try:
        from app.worker.consumer import start_consumer

        _consumer_task = asyncio.create_task(start_consumer())
        log.info("RabbitMQ consumer started")
    except Exception as exc:
        log.warning("Could not start RabbitMQ consumer: %s", exc)

    yield

    if _consumer_task and not _consumer_task.done():
        _consumer_task.cancel()
        try:
            await _consumer_task
        except asyncio.CancelledError:
            pass
    await engine.dispose()


app = FastAPI(title="PatchLens", version="1.0.0", lifespan=lifespan)

app.include_router(webhooks.router)
app.include_router(jobs.router)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}
