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
    finding_count       INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_review_jobs_pr_head_sha
        UNIQUE (repository_owner, repository_name, pull_request_number, head_sha)
);

CREATE TABLE IF NOT EXISTS review_findings (
    id                  UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    review_job_id       UUID         NOT NULL,
    file_path           VARCHAR(2000) NOT NULL,
    line_start          INT,
    line_end            INT,
    category            VARCHAR(50)  NOT NULL,
    severity            VARCHAR(20)  NOT NULL,
    title               VARCHAR(500) NOT NULL,
    explanation         CLOB         NOT NULL,
    confidence          DOUBLE       NOT NULL,
    evidence_json       CLOB,
    validation_status   VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS patch_suggestions (
    id                  UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    finding_id          UUID         NOT NULL,
    patch_text          CLOB         NOT NULL,
    rationale           CLOB,
    status              VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    repair_attempts     INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS patch_validations (
    id                      UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    patch_id                UUID         NOT NULL,
    patch_applied           BOOLEAN      NOT NULL DEFAULT FALSE,
    compile_passed          BOOLEAN      NOT NULL DEFAULT FALSE,
    static_analysis_passed  BOOLEAN      NOT NULL DEFAULT FALSE,
    tests_passed            BOOLEAN      NOT NULL DEFAULT FALSE,
    logs                    CLOB,
    duration_ms             BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_jobs_owner_repo_pr
    ON review_jobs (repository_owner, repository_name, pull_request_number);

CREATE TABLE IF NOT EXISTS evaluation_runs (
    id                  UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    pipeline_version    VARCHAR(50),
    model_name          VARCHAR(100),
    prompt_version      VARCHAR(50),
    dataset_version     VARCHAR(50),
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    precision_score     DOUBLE,
    recall_score        DOUBLE,
    patch_apply_rate    DOUBLE,
    compile_success_rate DOUBLE,
    test_pass_rate      DOUBLE
);

CREATE TABLE IF NOT EXISTS evaluation_case_results (
    id                  UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    run_id              UUID         NOT NULL,
    case_id             VARCHAR(100) NOT NULL,
    detected_findings   CLOB,
    true_positives      INT          NOT NULL DEFAULT 0,
    false_positives     INT          NOT NULL DEFAULT 0,
    false_negatives     INT          NOT NULL DEFAULT 0,
    patch_apply_success BOOLEAN      NOT NULL DEFAULT FALSE,
    compile_success     BOOLEAN      NOT NULL DEFAULT FALSE,
    test_success        BOOLEAN      NOT NULL DEFAULT FALSE,
    latency_ms          BIGINT       NOT NULL DEFAULT 0,
    token_usage         INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP
);
