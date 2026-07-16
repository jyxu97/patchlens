from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


# Lazily-initialised to avoid importing asyncpg at module load time.
# Tests that create their own engine (SQLite) import only `Base` and
# `async_sessionmaker` / `create_async_engine` directly.
_engine = None
_AsyncSessionLocal: async_sessionmaker | None = None


def get_engine():
    global _engine
    if _engine is None:
        from app.config import settings

        _engine = create_async_engine(settings.database_url, echo=False)
    return _engine


def get_session_factory() -> async_sessionmaker:
    global _AsyncSessionLocal
    if _AsyncSessionLocal is None:
        _AsyncSessionLocal = async_sessionmaker(
            get_engine(), class_=AsyncSession, expire_on_commit=False
        )
    return _AsyncSessionLocal




async def get_db():
    async with get_session_factory()() as session:
        yield session
