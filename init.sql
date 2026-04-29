-- Runs automatically when PostgreSQL container is first created.
-- Enables the pgvector extension so vector columns and <=> operator work.
CREATE EXTENSION IF NOT EXISTS vector;
