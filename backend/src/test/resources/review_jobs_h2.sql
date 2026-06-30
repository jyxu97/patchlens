-- H2 schema for ReviewJob deduplication tests.
-- Only the review_jobs table is created; other entities are not accessed by these tests.
-- ddl-auto=none is required to prevent Hibernate from attempting to create
-- all entities (which would fail on the vector(1536) column in RepositoryContextChunk).

CREATE TABLE IF NOT EXISTS review_jobs (
    id                  UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    repository_owner    VARCHAR(255) NOT NULL,
    repository_name     VARCHAR(255) NOT NULL,
    pull_request_number INT          NOT NULL,
    pull_request_url    VARCHAR(2000) NOT NULL,
    head_sha            VARCHAR(40),
    diff_hash           VARCHAR(255),
    status              VARCHAR(50)  NOT NULL,
    result_json         CLOB,
    error_message       CLOB,
    attempt_count       INT          NOT NULL DEFAULT 0,
    trigger_source      VARCHAR(50)  NOT NULL DEFAULT 'webhook',
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    CONSTRAINT uq_review_jobs_pr_head_sha
        UNIQUE (repository_owner, repository_name, pull_request_number, head_sha)
);

CREATE INDEX IF NOT EXISTS idx_review_jobs_owner_repo_pr
    ON review_jobs (repository_owner, repository_name, pull_request_number);
