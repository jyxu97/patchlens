# PatchLens

**AI-powered pull request review assistant** — summarizes PR diffs, flags risky files, and generates reviewer checklists using GPT and retrieved repository context.

**Live demo:** https://patchlens-alpha.vercel.app

![PatchLens demo](demo.gif)

---

## Features

- Analyze any public GitHub PR by URL, or try one of three built-in sample PRs without a GitHub token
- Rule-based risk scoring flags auth, payment, migration, and config changes before calling GPT
- RAG pipeline retrieves top-k repository context chunks from pgvector; file paths and similarity scores are returned alongside the review so the result is traceable, not a black box
- Structured GPT output with JSON schema validation produces typed summaries, risky-file annotations, and test checklists
- Grounding validation checks every AI-flagged file path against the actual PR diff and reports a grounding rate; hallucinated paths are logged per analysis run
- Prompt/model versioning records the active prompt version tag alongside every analysis run, enabling A/B evaluation when upgrading models or revising prompts
- Automated eval suite runs the full pipeline against all three built-in sample PRs in CI, asserting structural correctness and minimum expected risk levels without any external services
- Redis caching by diff hash skips repeated LLM calls when the PR hasn't changed
- Every analysis run logs GitHub API latency, retrieval latency, LLM latency, token usage, and cache-hit status to PostgreSQL
- Mock AI mode for local development without spending API credits

---

## Architecture

```
GitHub Webhook (PR opened/updated)
        |
        v
Spring Boot API  ──  WebhookController (POST /api/webhooks/github)
        |                validates HMAC-SHA256 signature
        |                creates ReviewJob (PENDING), idempotency check
        |
        v
RabbitMQ  ──  review.jobs queue
        |        3-attempt exponential-backoff retry (2s/4s/8s)
        |        dead-letter queue on exhaustion
        v
ReviewJobWorker  ──  consumes queue messages
        |
        |-- GitHubService        fetch PR metadata and changed files
        |-- DiffParserService    normalize diffs, compute SHA-256 hash
        |-- RiskScoringService   score files using deterministic rules
        |-- CacheService         check Redis for existing analysis
        |-- ContextRetrieval          retrieve top-k chunks from pgvector
        |-- OpenAIService             generate structured review brief
        |-- GroundingValidation       check AI file paths against actual diff
        |-- ReviewJobService          update job status (PROCESSING → COMPLETED)
        |
        |-- PostgreSQL + pgvector   review jobs, sessions, context chunks
        |-- Redis                   diff-hash result cache
        |
        v
JobStatusEmitter  ──  SSE push to client (PENDING → PROCESSING → COMPLETED)
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 4 |
| Frontend | React, TypeScript, Vite, Tailwind CSS |
| Database | PostgreSQL + pgvector |
| Cache | Redis |
| Message Queue | RabbitMQ |
| AI | OpenAI Chat Completions, OpenAI Embeddings |
| External API | GitHub REST API |
| Deployment | Vercel (frontend) |
| CI/CD | GitHub Actions |
| Containerization | Docker, Docker Compose |

---

## How It Works

1. User enters a GitHub PR URL (or clicks **Try Sample PR**)
2. Backend fetches PR metadata and changed files from GitHub API
3. Computes a SHA-256 hash of the normalized diff — checks Redis cache
4. On cache miss: runs rule-based risk scoring; if the repo has never been indexed or the index is stale (tree SHA changed), triggers async file discovery — fetches the full repository tree, scores every file by path/name/extension, and embeds the top 50 highest-signal files into pgvector; then retrieves top-k repository context chunks, calls OpenAI with the assembled prompt
5. Response includes the review brief, per-file risk scores, and retrieved context chunks with file paths and similarity scores
6. Result is cached in Redis and persisted to PostgreSQL; latency breakdown and token usage are logged to the `analysis_runs` table
7. Frontend displays the structured review brief and the retrieved context that grounded it

---

## Local Setup

**Prerequisites:** Java 21, Docker, Node.js 20

### 1. Clone the repository

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

`GITHUB_TOKEN` is optional for public repos but increases the API rate limit.
Set `AI_MODE=mock` to skip OpenAI calls during development.

### 3. Start all backend services

```bash
docker compose up --build
```

This starts the Spring Boot backend, PostgreSQL (with pgvector), Redis, and RabbitMQ in Docker.

RabbitMQ management UI is available at http://localhost:15672 (username/password: `patchlens`).

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
| GET | `/api/jobs/{id}` | Get current status of a review job |
| GET | `/api/jobs/{id}/stream` | SSE stream of job status updates |
| POST | `/api/repositories/index` | Index repository docs for RAG |

---

## Sample PR Demo

The homepage offers three built-in sample PRs that run the full analysis pipeline without requiring a GitHub token or OpenAI key (in mock mode):

| Sample | Scenario | Expected Risk |
|--------|----------|---------------|
| Redis Session Cache | Replaces in-memory sessions with Redis-backed caching | Medium |
| Auth DB Migration | Migrates auth to OAuth 2.0, drops legacy password columns | High |
| Stripe Checkout | Adds Stripe subscription billing and webhook handler | High |

Each sample exercises different risk scoring rules and produces a different AI review brief. Pre-seeded repository context chunks ensure the Retrieved Context panel is populated for all three samples, demonstrating the full RAG pipeline without requiring a real GitHub repository.

---

## Cost Control

- **Diff hash caching** — identical diffs return cached results instantly, no LLM call
- **Rule-based pre-filtering** — risk scores are computed deterministically before GPT
- **Token budgets** — max 20 files and 4,000 chars per patch sent to OpenAI
- **Mock mode** — `AI_MODE=mock` returns fixture results without any API calls
- **Top-k retrieval** — only the 5 most relevant context chunks are included in the prompt

---

## Observability

Every analysis request — cache hit or miss — writes one row to the `analysis_runs` table:

| Field | Description |
|-------|-------------|
| `cache_hit` | Whether the result was served from Redis |
| `github_latency_ms` | Time spent fetching PR metadata and files from GitHub API (0 for sample PRs) |
| `retrieval_latency_ms` | Time spent in pgvector top-k retrieval |
| `llm_latency_ms` | Time waiting for the OpenAI API response |
| `total_latency_ms` | End-to-end wall-clock time for the request |
| `prompt_tokens` | Input tokens consumed by the model |
| `completion_tokens` | Output tokens consumed by the model |
| `model_name` | Model used (`gpt-4o-mini`, `mock`, or `cached`) |
| `hallucinated_ref_count` | Number of AI-flagged file paths not present in the actual diff (null on cache hits) |
| `grounding_rate` | Fraction of AI-flagged paths that were grounded in the diff (null on cache hits) |
| `prompt_version_id` | FK to `prompt_versions` — records which prompt + model combination was active |
| `status` | `success` or `error` |

This makes it straightforward to distinguish whether latency comes from GitHub API fetch, pgvector retrieval, or OpenAI generation — and to correlate AI output quality with specific prompt versions.

---

## Structured Output Validation

OpenAI is called with `response_format: json_object` and a strict JSON schema. The response is validated before being returned to the client:

- All required fields (`summary`, `riskAssessment`, `suggestedTests`, `reviewChecklist`) must be present
- Missing or malformed responses raise a runtime error rather than returning partial data

---

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `GITHUB_TOKEN` | GitHub personal access token | *(empty, public repos only)* |
| `OPENAI_API_KEY` | OpenAI API key | *(required for real AI mode)* |
| `AI_MODE` | `mock` or `openai` | `mock` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins | `http://localhost:5173` |
| `OPENAI_MODEL` | GPT model to use | `gpt-4o-mini` |
| `GITHUB_WEBHOOK_SECRET` | Secret for HMAC-SHA256 webhook validation | *(empty, skips validation)* |
| `PROMPT_VERSION` | Version tag written to `prompt_versions` table | `v1` |
| `PROMPT_NOTES` | Human-readable description of this prompt version | `Initial version with gpt-4o-mini` |

---

## Future Improvements

- GitHub App integration for automatic PR comment posting
- Support for private repositories via GitHub OAuth
- Repository-wide indexing and chunk reranking
- Historical analytics on risky PR patterns
