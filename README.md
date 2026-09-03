# PatchLens

**AI-powered pull request review assistant** — multi-step analysis pipeline that detects issues, generates validated unified diff patches, and posts a structured review comment directly on GitHub PRs.

Live demo: [patchlens-alpha.vercel.app](https://patchlens-alpha.vercel.app)

---

## Features

- **Multi-step AI review** — three focused LLM calls: diff understanding → evidence-grounded issue detection → per-finding patch generation; each step builds on the previous rather than relying on one monolithic prompt
- **Evidence-grounded findings** — every detected issue must cite exact file and line number from the diff; a deterministic filter rejects hallucinated file paths, out-of-range lines, low-confidence results, and duplicate overlapping ranges
- **Automated patch generation** — for HIGH and MEDIUM severity findings, generates minimal unified diff patches with format and size validation (max 200 lines, must reference a PR file)
- **Docker sandbox validation** — applies each patch inside a `maven:3.9-eclipse-temurin-21` container and runs the project's own compile and test commands; compile/test results are recorded per patch
- **Bounded repair loop** — if a patch fails validation, feeds the error output back to the model for up to 2 repair attempts before giving up
- **GitHub PR comments** — after analysis completes, posts a single structured Markdown comment on the PR listing all findings and suggested patches (requires `GITHUB_TOKEN`)
- **RAG context retrieval** — pgvector cosine similarity retrieval augments LLM calls with relevant project documentation and source context; retrieval queries are derived from the diff itself
- **Rule-based risk scoring** — flags auth, payment, migration, and config changes deterministically before any LLM call
- **Redis deduplication and caching** — `SET NX` atomic gate suppresses duplicate webhook deliveries; diff-hash caching skips LLM calls for unchanged PRs
- **Offline evaluation harness** — runs the full pipeline against built-in sample PRs and computes precision, recall, patch apply rate, compile success rate, and test pass rate
- **Full observability** — every run logs GitHub API, retrieval, and LLM latency, token usage, grounding rate, finding count, and validation outcomes to PostgreSQL
- **Mock AI mode** — returns fixture results without any API or Docker calls, for local development and CI

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
2. Backend validates the HMAC-SHA256 signature, calls Redis `SET NX` as an atomic dedup gate — duplicate deliveries return 202 immediately with no DB write. A `ReviewJob` (status: PENDING) is created and published to RabbitMQ.
3. `ReviewJobWorker` runs three sequential stages:

   **Stage 1 — Finding detection**
   - Fetches PR metadata and changed files from GitHub API
   - Computes a SHA-256 hash of the normalized diff; checks Redis cache
   - On cache miss: scores files deterministically, retrieves top-k context chunks from pgvector, calls LangChain4j `ReviewAiService` with versioned prompts, applies deterministic filter to raw findings
   - Persists `ReviewFinding` entities

   **Stage 2 — Patch generation + validation**
   - For each HIGH/MEDIUM finding: calls `PatchAiService` to generate a unified diff
   - Validates patch format and size (starts with `---`, max 200 lines, references a PR file)
   - Runs the patch inside a Docker container against the PR's base content; records compile and test outcomes
   - On failure: feeds error output back to `RepairAiService` for up to 2 repair attempts
   - Persists `PatchSuggestion` and `PatchValidation` entities

   **Stage 3 — GitHub comment**
   - Formats all findings and patches as a Markdown comment
   - Posts the comment to the PR via GitHub Issues API (`POST /repos/{owner}/{repo}/issues/{pr}/comments`)

4. `JobStatusEmitter` pushes each status transition to the client via SSE (`GET /api/jobs/{id}/stream`)

### Manual flow (UI / API)

1. User enters a GitHub PR URL (or clicks **Try Sample PR**) in the web UI
2. `POST /api/reviews/analyze` runs the diff hash + risk scoring + RAG + LLM pipeline synchronously and returns the full review brief in one response
3. Frontend displays the structured review brief and the retrieved context chunks that grounded it

---

## Repository Config (`patchlens.yaml`)

Place this file in the root of the target repository to control how the Docker sandbox builds and tests the project:

```yaml
language: java
build:
  command: "./mvnw -q -DskipTests test-compile"
tests:
  command: "./mvnw -q test"
timeouts:
  compile_seconds: 120
  test_seconds: 300
```

If the file is absent, PatchLens uses these defaults. The `staticAnalysis.command` field is optional; leave it blank to skip that step.

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
- Set `AI_MODE=mock` to skip all OpenAI and Docker calls during development

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

`POST /api/eval/run` runs the full pipeline against all built-in sample PRs and records:

- **Precision** — fraction of detected findings that match an expected finding
- **Recall** — fraction of expected findings that were detected
- **Patch apply rate** — fraction of patches that applied cleanly in the sandbox
- **Compile success rate** and **test pass rate** per eval run

Results are queryable via `GET /api/eval/runs`.

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
