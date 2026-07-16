"""aio-pika RabbitMQ consumer with exponential-backoff retry and dead-letter queue.

Topology
--------
Exchange : patchlens.reviews        (direct, durable)
  Queue  : review.jobs              (durable, x-dead-letter-exchange → patchlens.reviews.dlx)
    3 retries with 2 s / 4 s / 8 s back-off
      DLX Exchange : patchlens.reviews.dlx
        Queue      : review.jobs.dlq
"""

from __future__ import annotations

import asyncio
import json
import logging

import aio_pika
from aio_pika import ExchangeType, Message
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_session_factory
from app.services.analysis import run_analysis

log = logging.getLogger(__name__)

EXCHANGE = "patchlens.reviews"
QUEUE = "review.jobs"
DLX_EXCHANGE = "patchlens.reviews.dlx"
DLQ = "review.jobs.dlq"
ROUTING_KEY = "review.job"
MAX_RETRIES = 3
BACKOFF_BASE = 2  # seconds


async def _declare_topology(channel: aio_pika.abc.AbstractChannel) -> aio_pika.Queue:
    dlx = await channel.declare_exchange(DLX_EXCHANGE, ExchangeType.DIRECT, durable=True)
    dlq = await channel.declare_queue(DLQ, durable=True)
    await dlq.bind(dlx, routing_key=QUEUE)

    exchange = await channel.declare_exchange(EXCHANGE, ExchangeType.DIRECT, durable=True)
    queue = await channel.declare_queue(
        QUEUE,
        durable=True,
        arguments={"x-dead-letter-exchange": DLX_EXCHANGE},
    )
    await queue.bind(exchange, routing_key=ROUTING_KEY)
    return queue


async def _process(body: bytes, session: AsyncSession) -> None:
    payload = json.loads(body)
    await run_analysis(
        session=session,
        job_id=payload["job_id"],
        owner=payload["owner"],
        repo=payload["repo"],
        pull_number=payload["pull_number"],
    )


async def start_consumer() -> None:
    connection = await aio_pika.connect_robust(settings.rabbitmq_url)
    async with connection:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=1)
        queue = await _declare_topology(channel)

        log.info("Worker listening on queue '%s'", QUEUE)

        async with queue.iterator() as messages:
            async for message in messages:
                retry = int(message.headers.get("x-retry-count", 0))
                try:
                    async with get_session_factory()() as session:
                        await _process(message.body, session)
                    await message.ack()
                except Exception as exc:
                    if retry < MAX_RETRIES:
                        delay = BACKOFF_BASE ** (retry + 1)
                        log.warning("Job failed (attempt %d/%d), retrying in %ds: %s", retry + 1, MAX_RETRIES, delay, exc)
                        await asyncio.sleep(delay)
                        exchange = await channel.get_exchange(EXCHANGE)
                        await exchange.publish(
                            Message(
                                body=message.body,
                                headers={"x-retry-count": retry + 1},
                                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                            ),
                            routing_key=ROUTING_KEY,
                        )
                        await message.ack()
                    else:
                        log.error("Job exceeded max retries, sending to DLQ: %s", exc)
                        await message.nack(requeue=False)


async def publish_job(job_id: int, owner: str, repo: str, pull_number: int) -> None:
    connection = await aio_pika.connect_robust(settings.rabbitmq_url)
    async with connection:
        channel = await connection.channel()
        exchange = await channel.declare_exchange(EXCHANGE, ExchangeType.DIRECT, durable=True)
        body = json.dumps(
            {"job_id": job_id, "owner": owner, "repo": repo, "pull_number": pull_number}
        ).encode()
        await exchange.publish(
            Message(body=body, delivery_mode=aio_pika.DeliveryMode.PERSISTENT),
            routing_key=ROUTING_KEY,
        )
