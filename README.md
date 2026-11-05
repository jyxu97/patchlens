# PatchLens

**AI-powered pull request review assistant** — multi-step analysis pipeline that detects issues, generates validated unified diff patches, and posts a structured review comment directly on GitHub PRs.

Live demo: [patchlens-alpha.vercel.app](https://patchlens-alpha.vercel.app)

---

## Features

- **Multi-step AI review** — three focused LLM calls: diff understanding → evidence-grounded issue detection → per-finding patch generation; each step builds on the previous rather than relying on one monolithic prompt
- **Evidence-grounded findings** — a deterministic filter separates *syntactic validity* (required fields), *repository grounding* (file is in the PR and not deleted; reported lines fall on new-side diff hunk lines; evidence citations outside the diff are stripped) and leaves *behavioral validation* to the sandbox. Low-confidence findings are dropped; duplicates (same file + category + overlapping lines) keep the highest-confidence one, deterministically
- **Automated patch generation** — for HIGH and MEDIUM severity findings, generates minimal unified diff patches with format and size validation (max 200 lines, must reference a PR file)
- **Docker sandbox validation** — applies each patch inside a `maven:3.9-eclipse-temurin-21` container and runs the project's own compile and test commands; compile/test results are recorded per patch
- **Bounded repair loop** — if a patch fails validation, feeds the error output back to the model for up to 2 repair attempts before giving up
- **GitHub PR comments** — after analysis completes, posts a single structured Markdown comment on the PR listing all findings and suggested patches (requires `GITHUB_TOKEN`)
- **RAG context retrieval** — pgvector cosine similarity retrieval augments LLM calls with relevant project documentation and source context; retrieval queries are derived from the diff itself
- **Rule-based risk scoring** — flags auth, payment, migration, and config changes deterministically before any LLM call
- **Redis deduplication and caching** — `SET NX` atomic gate suppresses duplicate webhook deliveries; diff-hash caching skips LLM calls for unchanged PRs
- **Offline evaluation harness** — runs the full pipeline against built-in sample PRs and computes precision, recall, patch apply rate, compile success rate, and test pass rate
- **Full observability** — every run logs GitHub API, retrieval, and LLM latency, token usage, grounding rate, finding count, and validation outcomes to PostgreSQL
- **Mock AI mode** — returns fixture results without any OpenAI calls, for local development and CI (mock patches are never reported as validated)

---

## Architecture

```
GitHub Webhook (PR opened/synchronize/reopened)
        |
        v
WebhookController  POST /api/webhooks/github
        |  validates HMAC-SHA256 signature
        |  Redis SET NX dedup gate (24 h TTL)
        |  creates ReviewJob (PENDING)
        |
        v
RabbitMQ  review.jobs queue
        |  3-attempt exponential-backoff retry (2s / 4s / 8s)
        |  dead-letter queue on exhaustion
        v
ReviewJobWorker  ── three pipeline stages
        |
        |  Stage 1 — GENERATING_FINDINGS
        |    GitHubService         fetch PR metadata + changed files
        |    DiffParserService     normalize diff, SHA-256 hash
        |    RiskScoringService    deterministic file-level risk scores
        |    CacheService          Redis diff-hash cache check
        |    IssueDetectionService (if cache miss)
        |      ├─ OpenAIService.analyzeDiff()        diff understanding
        |      ├─ ContextRetrievalService.retrieve() pgvector top-k
        |      ├─ ReviewAiService.review()           LangChain4j structured findings
        |      └─ FindingFilterService               deterministic filter
        |    GroundingValidationService  check AI paths vs. actual diff
        |    ReviewFindingRepository     persist findings
        |
        |  Stage 2 — GENERATING_PATCHES
        |    PatchGenerationService  (HIGH + MEDIUM findings only)
        |      ├─ PatchAiService.generatePatch()     LangChain4j unified diff
        |      ├─ PatchValidationService             Docker sandbox compile + test
        |      └─ RepairAiService.repair()           up to 2 repair attempts on failure
        |    PatchSuggestionRepository  persist patches + validation results
        |
        |  Stage 3 — COMPLETED
        |    ReviewCommentService   format Markdown, call GitHub Issues API
        |
        v
JobStatusEmitter  SSE push to client  PENDING → PROCESSING → COMPLETED
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 4, Spring Framework 7 |
| AI framework | LangChain4j 1.19.0 (core + openai) |
| Frontend | React, TypeScript, Vite, Tailwind CSS |
| Database | PostgreSQL + pgvector |
| Cache | Redis |
| Message queue | RabbitMQ |
| AI models | OpenAI GPT-4o-mini (review, patch, repair), text-embedding-3-small |
| Sandbox | Docker (`maven:3.9-eclipse-temurin-21`) via ProcessBuilder |
| External API | GitHub REST API |
| Deployment | Vercel (frontend), Railway (backend + PostgreSQL + Redis) |
| CI/CD | GitHub Actions |
| Containerization | Docker, Docker Compose |

---

## How It Works

### Webhook flow (primary)

1. GitHub sends a `pull_request` event to `POST /api/webhooks/github`
2. Backend validates the HMAC-SHA256 signature, calls Redis `SET NX` as an atomic dedup gate — duplicate deliveries return 202 immediately with no DB write. A `ReviewJob` (status: PENDING) is created (DB unique constraint on repo + PR + head SHA) and published to RabbitMQ through the job-row outbox.
3. `ReviewJobWorker` claims the job atomically (row lock + expiring lease) and runs the stages below; every transition is fenced by the lease.

   **Stage 1 — Finding detection**
   - Fetches PR metadata and changed files from GitHub API
   - Computes a SHA-256 hash of the normalized diff; checks Redis cache
   - On cache miss: scores files deterministically, retrieves top-k context chunks from pgvector, calls LangChain4j `ReviewAiService` with versioned prompts, applies deterministic filter to raw findings
   - Persists `ReviewFinding` entities

   **Stage 2 — Patch generation (`GENERATING_PATCHES`)**
   - For each HIGH/MEDIUM finding: calls `PatchAiService` to generate a unified diff (persisted as `PENDING`)

   **Stage 3 — Sandbox validation + bounded repair (`VALIDATING_PATCHES`)**
   - Deterministic patch policy: parseable unified diff, ≤200 lines, only the finding's file, no protected build/CI files, no path traversal
   - Materialises a fresh snapshot of the repository at the **pinned PR head SHA** (GitHub tarball, extracted in-process), loads `patchlens.yaml` from the pristine tree, applies the patch in-process
   - Prefetches dependencies (network on, build descriptors only), then runs compile → optional static analysis → tests in Docker with networking disabled
   - Repairable failures (policy, apply, compile, static analysis, tests) are fed back to `RepairAiService`; at most 2 repairs, each validated in a new, clean workspace
   - Every attempt is persisted as a `PatchValidation` row (status, failing step, error output, patch text, revision)
   - `VALIDATED` means the patch applied and every configured check that ran passed. If Docker is unavailable the patch is `NOT_VALIDATED`; infrastructure failures are `VALIDATION_ERROR` — neither is ever shown as a fix
   - If the PR head moves while the job runs, the job ends as `SUPERSEDED` and the newer commit's job reviews it

   **Stage 3 — GitHub comment**
   - Formats all findings and patches as a Markdown comment
   - Posts the comment to the PR via GitHub Issues API (`POST /repos/{owner}/{repo}/issues/{pr}/comments`)

4. `JobStatusEmitter` pushes each status transition to the client via SSE (`GET /api/jobs/{id}/stream`)

### Manual flow (UI / API)

1. User enters a GitHub PR URL (or clicks **Try Sample PR**) in the web UI
2. `POST /api/reviews/analyze` runs the diff hash + risk scoring + RAG + LLM pipeline synchronously and returns the full review brief in one response
3. Frontend displays the structured review brief and the retrieved context chunks that grounded it

---

### RAG freshness and large PRs

- **Versioned index** — chunks are tagged with the default-branch tree SHA. A re-index fetches every file at one pinned commit, writes the new version beside the active one, switches the active version in a single row update, then deletes old chunks. Retrieval reads only the active version, so an analysis never sees a partial or mixed index; a failed re-index leaves the previous version active. One re-index per repository runs at a time.
- **Explicit freshness** — each analysis records `FRESH` / `STALE` (async re-index triggered) / `UNKNOWN` (current tree not determinable, or untracked seeded chunks) / `NOT_INDEXED`, shown in the PR comment footer.
- **Large PRs** — changed files are fetched with pagination (up to GitHub's 3000-file listing limit). `ReviewPlanner` deterministically picks files by risk score (`max.changed.files`, default 20), truncates each diff (`max.patch.chars.per.file`, 4000) and packs them into at most `review.max-batches` (3) model calls of `review.batch-char-budget` (32 000) characters. Every skipped file (`NO_TEXT_DIFF`, `REMOVED`, `FILE_LIMIT`, `BATCH_LIMIT`) and truncated diff is stored in the job's `coverage_json` and summarised in the PR comment; findings on files the model did not see are rejected as ungrounded.

### Reliability model

RabbitMQ gives at-least-once delivery, not exactly-once processing. PatchLens makes repeated delivery safe at the job and output level:

| Failure | Handling |
|---|---|
| Duplicate webhook | Redis `SET NX` claim (provisional 5 min TTL, extended to 24 h after the job row commits); DB unique constraint on (repo, PR, head SHA) is the source of truth |
| Job row cannot be written | Redis claim released, `503` returned so GitHub can redeliver |
| RabbitMQ down when the webhook arrives | Job row is committed first and acts as the outbox (`publishedAt` null); `ReliabilityScheduler` republishes it |
| Duplicate / redelivered message | `claim()` locks the row (`SELECT … FOR UPDATE`): terminal jobs and jobs with a live lease are not processed again |
| Two workers, same job | Only one holds the lease; all later writes check the lease token |
| Worker crash | Heartbeat stops, lease expires, scheduler republishes, a new delivery takes over; the stale worker's writes are rejected (`LeaseLostException`) |
| Transient error | Lease released, Spring AMQP retries (3 attempts, exponential backoff), then DLQ + `DEAD_LETTER` |
| GitHub comment fails / times out | Analysis is `COMPLETED` and the rendered comment is stored with `commentStatus=PENDING`; delivery is retried independently with backoff (`FAILED_RETRYABLE` → `DELIVERED`, or `FAILED_PERMANENT` after 6 attempts / non-retryable 4xx) without re-running the LLM |
| POST accepted but response lost | Every attempt first searches for the job's hidden marker `<!-- patchlens:job=<id> -->` and updates instead of creating — no duplicates |
| Newer commit pushed mid-review | Job ends `SUPERSEDED`; a comment for an outdated head is `SKIPPED_STALE` |
| Restart | All of the above state is in PostgreSQL; the scheduler resumes pending publishes and comment deliveries |

---

## Repository Config (`patchlens.yaml`)

Place this file in the root of the target repository to control how the Docker sandbox builds and tests the project:

```yaml
language: java
buildFile: pom.xml
build:
  command: "mvn -q -DskipTests test-compile"
staticAnalysis:
  command: ""            # blank = step disabled (default)
tests:
  command: "mvn -q test"
timeouts:
  compileSeconds: 300
  staticAnalysisSeconds: 120
  testSeconds: 300
```

If the file is absent, PatchLens uses these defaults. Commands run inside the sandbox container (`sh -c`, network disabled); the sandbox injects Maven offline mode and the dependency-cache layout via `MAVEN_ARGS`, so don't pass `-o` or `-Dmaven.repo.local`. The file is read from the pristine snapshot, parsed with SnakeYAML's safe constructor, and timeouts are capped by `patchlens.sandbox.max-step-seconds`.

**Sandbox isolation** (`DockerSandboxExecutor`): `--network none`, non-root user, `--cap-drop ALL`, `no-new-privileges`, read-only root filesystem with a size-limited `/tmp` tmpfs, CPU/memory/PID limits, hard wall-clock timeout that kills the container, only the disposable workspace, scratch dir and a read-only dependency cache mounted (never the Docker socket), no host environment forwarded.

**Running the sandbox:** the backend needs a Docker CLI that can reach a daemon, and the sandbox work directory must be a path the daemon can bind-mount. Run the backend on the host (`./mvnw spring-boot:run`) for real validation; the Docker Compose `backend` container has no Docker CLI, so it records patches as `NOT_VALIDATED`. Set `SANDBOX_ENABLED=false` to disable the sandbox explicitly.

---

## Local Setup

**Prerequisites:** Java 21, Docker, Node.js 20

### 1. Clone

```bash
git clone https://github.com/jyxu97/patchlens.git
cd patchlens
```

### 2. Configure environment variables

Create a `.env` file in the project root:

```
GITHUB_TOKEN=your_github_personal_access_token
OPENAI_API_KEY=your_openai_api_key
AI_MODE=openai
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

- `GITHUB_TOKEN` is optional for public repos but increases the rate limit and is required for posting PR review comments
- Set `AI_MODE=mock` to skip all OpenAI calls during development (sandbox validation still runs if Docker is available; mock patches will fail it, as they should)

### 3. Start backend services

```bash
docker compose up --build
```

Starts Spring Boot, PostgreSQL (with pgvector), Redis, and RabbitMQ.

RabbitMQ management UI: http://localhost:15672 (credentials: `patchlens` / `patchlens`)

### 4. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173

---

## Running Tests

```bash
cd backend
./mvnw test                                           # everything (contextLoads needs `docker compose up -d postgres redis rabbitmq`)
./mvnw test -Dtest=DockerSandboxIntegrationTest       # real Docker sandbox: compile/test a fixture, timeouts, isolation checks
```

`DockerSandboxIntegrationTest` is skipped (reported as not executed) when no Docker daemon is reachable; it is never replaced by a mock. The first run pulls `maven:3.9.9-eclipse-temurin-21` and resolves fixture dependencies into `backend/target/sandbox-it`.

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Health check |
| POST | `/api/reviews/analyze` | Analyze a GitHub PR by URL (synchronous) |
| POST | `/api/reviews/analyze-sample` | Analyze a built-in sample PR |
| GET | `/api/reviews/{id}` | Fetch a saved review by session ID |
| POST | `/api/webhooks/github` | Receive GitHub PR webhook events |
| GET | `/api/jobs/{id}` | Get current job status |
| GET | `/api/jobs/{id}/stream` | SSE stream of job status transitions |
| POST | `/api/repositories/index` | Index repository docs into pgvector |
| GET | `/api/metrics` | Aggregate analysis + patch + validation metrics |
| POST | `/api/eval/run` | Trigger an offline evaluation run |
| GET | `/api/eval/runs` | List evaluation runs with aggregate scores |
| GET | `/api/eval/runs/{id}` | Detailed per-case evaluation results |

---

## Sample PRs

Three built-in sample PRs demonstrate the full pipeline without a GitHub token or OpenAI key (in mock mode):

| Sample | Scenario | Expected Risk |
|--------|----------|---------------|
| Redis Session Cache | Replaces in-memory sessions with Redis | Medium |
| Auth DB Migration | Migrates auth to OAuth 2.0, drops legacy password columns | High |
| Stripe Checkout | Adds Stripe subscription billing + webhook handler | High |

Pre-seeded context chunks ensure the Retrieved Context panel is populated for all three, demonstrating the RAG pipeline without a live repository.

---

## Observability

Every analysis run writes one row to `analysis_runs`:

| Field | Description |
|-------|-------------|
| `cache_hit` | Whether result was served from Redis |
| `github_latency_ms` | Time fetching PR data from GitHub API |
| `retrieval_latency_ms` | Time in pgvector top-k retrieval |
| `llm_latency_ms` | Time waiting for OpenAI response |
| `total_latency_ms` | End-to-end wall-clock time |
| `prompt_tokens` / `completion_tokens` | Token usage |
| `model_name` | `gpt-4o-mini`, `mock`, or `cached` |
| `hallucinated_ref_count` | AI-flagged paths not in the actual diff |
| `grounding_rate` | Fraction of AI paths grounded in the diff |
| `prompt_version_id` | FK to `prompt_versions` |

`GET /api/metrics` aggregates across all runs:

| Metric | Description |
|--------|-------------|
| `totalAnalyses` | Successful analysis count |
| `cacheHitRate` | Fraction served from cache |
| `avgCacheMissLatencyMs` / `avgCacheHitLatencyMs` | Latency breakdown |
| `findingsTotal` | Total `ReviewFinding` rows created |
| `patchGeneratedTotal` | Total patch suggestions generated |
| `patchApplySuccessRate` | Fraction of patches that applied cleanly |
| `compileSuccessRate` | Fraction of patches that compiled |
| `testPassRate` | Fraction of patches that passed tests |
| `repairAttemptRate` | Average repair attempts per patch |

---

## Evaluation Harness

### Dataset (`backend/src/main/resources/eval/dataset-v2`)

5 executable PR cases (`auth-001`, `cache-001`, `payment-001`, `pagination-001`, `resource-001`) with 8 labeled expected findings, 5 of which are patch targets. Each case has:

| Path | Purpose |
|---|---|
| `case.json` | PR title/body, changed files, labeled findings (`id`, `category`, `file`, `lineStart`–`lineEnd`, `patchTarget`), reference fix |
| `head/` | Complete Maven project at the PR head — compiles, and its own tests pass |
| `oracle/` | Hidden tests overlaid only during patch validation — fail on the labeled bug, pass after a correct fix. Never shown to the model |

The PR shown to the model contains the changed files as added files, so diff line numbers equal file line numbers. `EvalFixtureDockerIT` proves for every case, with real Docker builds, that the head passes its own tests, the oracle fails on the unfixed code, and the reference fix passes.

### Metrics

- **Matching** (`EvalMatcher`): a detection matches a label iff the file is identical, the category is equal, and the line ranges overlap (label widened by ±2 lines). Assignment is a deterministic one-to-one maximum matching, so duplicate detections of one issue count as one TP plus FPs.
- **Precision** = TP / (TP + FP), **recall** = TP / (TP + FN), micro-averaged over all cases.
- **Patch validation**: denominator = all labeled patch targets in the dataset (fixed). For each target matched by a HIGH/MEDIUM detection, the production `PatchPipelineService` generates a patch and validates it in the Docker sandbox against `head/ + oracle/`, with ≤2 repairs. Undetected targets count as failures.
  - initial pass rate = targets whose first candidate was `VALIDATED` / targets
  - final pass rate = targets whose final candidate was `VALIDATED` / targets
  - improvement = (final − initial) × 100 **percentage points**
  - if the sandbox is unavailable, patch rates are stored as `null`, never as measured values
- Each run persists: dataset version + content fingerprint, prompt-template fingerprint, AI mode, model, inference config (temperature, max tokens, repair budget, sandbox image, match rule), per-case labels, model findings, matches, initial/final patch text, validation statuses and repair counts.

### Reproducing a run

```bash
docker compose up -d postgres redis rabbitmq
cd backend
AI_MODE=openai OPENAI_API_KEY=... ./mvnw spring-boot:run     # on the host, so the Docker sandbox is usable

curl -X POST localhost:8080/api/eval/run                      # → {"runId": "..."}
curl localhost:8080/api/eval/runs/<runId>                     # poll until status != RUNNING
curl localhost:8080/api/eval/runs/<runId>/export > eval-run.json
```

Compare runs only when `datasetFingerprint`, `promptFingerprint` and `inferenceConfig` match. A run with `aiMode: "mock"` exercises the harness but is not a model evaluation (the mock detector returns a placeholder finding, so recall is 0).

Harness self-tests (real Docker, no model): `./mvnw test -Dtest='EvalFixtureDockerIT,EvaluationHarnessDockerIT'`.

---

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `GITHUB_TOKEN` | GitHub personal access token (rate limiting + PR comments) | *(empty)* |
| `OPENAI_API_KEY` | OpenAI API key | *(required for `openai` mode)* |
| `AI_MODE` | `mock` or `openai` | `mock` |
| `OPENAI_MODEL` | Chat model | `gpt-4o-mini` |
| `OPENAI_EMBEDDING_MODEL` | Embedding model | `text-embedding-3-small` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins | `http://localhost:5173` |
| `GITHUB_WEBHOOK_SECRET` | HMAC-SHA256 webhook validation secret | *(empty, skips validation)* |
| `PROMPT_VERSION` | Version tag written to `prompt_versions` | `v1` |
| `PROMPT_NOTES` | Description of this prompt version | `Initial version` |
