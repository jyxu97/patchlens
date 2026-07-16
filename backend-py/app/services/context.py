"""pgvector RAG retrieval.

Fetches the top-k most semantically similar past review snippets from the
`review_embeddings` table to ground the OpenAI prompt.
"""

from __future__ import annotations

from openai import AsyncOpenAI
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings

_client = AsyncOpenAI(api_key=settings.openai_api_key)


async def embed(text_content: str) -> list[float]:
    resp = await _client.embeddings.create(model=settings.embedding_model, input=text_content)
    return resp.data[0].embedding


async def retrieve_context(session: AsyncSession, query: str) -> list[str]:
    """Return up to top_k relevant review snippets via cosine similarity."""
    try:
        vector = await embed(query)
        vector_literal = "[" + ",".join(str(v) for v in vector) + "]"
        rows = await session.execute(
            text(
                """
                SELECT content
                FROM review_embeddings
                ORDER BY embedding <=> CAST(:vec AS vector)
                LIMIT :k
                """
            ),
            {"vec": vector_literal, "k": settings.vector_top_k},
        )
        return [row[0] for row in rows.fetchall()]
    except Exception:
        # pgvector not available in test/dev environments – degrade gracefully.
        return []
