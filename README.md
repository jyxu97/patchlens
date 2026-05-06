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
- Redis caching by diff hash skips repeated LLM calls when the PR hasn't changed
- Every analysis run logs retrieval latency, LLM latency, token usage, and cache-hit status to PostgreSQL
- Mock AI mode for local development without spending API credits

---

## Architecture

```
GitHub PR URL / Sample PR
        |
        v
React Frontend (Vercel)
        |
        v
Spring Boot API (Railway)
        |
        |-- GitHubService        fetch PR metadata and changed files
        |-- DiffParserService    normalize diffs, compute SHA-256 hash
        |-- RiskScoringService   score files using deterministic rules
        |-- CacheService         check Redis for existing analysis
        |-- ContextRetrieval     retrieve top-k chunks from pgvector
        |-- OpenAIService        generate structured review brief
        |-- AnalysisRunRepo      log latency, tokens, cache-hit per run
        |
        |-- PostgreSQL + pgvector   review sessions, context chunks, analysis runs
        |-- Redis                   diff-hash result cache
        |
        v
Review Result Dashboard
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 4 |
| Frontend | React, TypeScript, Vite, Tailwind CSS |
| Database | PostgreSQL + pgvector |
| Cache | Redis |
| AI | OpenAI Chat Completions, OpenAI Embeddings |
| External API | GitHub REST API |
| Deployment | Vercel (frontend), Railway (backend) |
| CI/CD | GitHub Actions |
| Containerization | Docker, Docker Compose |

---

## How It Works

1. User enters a GitHub PR URL (or clicks **Try Sample PR**)
2. Backend fetches PR metadata and changed files from GitHub API
3. Computes a SHA-256 hash of the normalized diff — checks Redis cache
4. On cache miss: runs rule-based risk scoring, retrieves top-k repository context chunks from pgvector, calls OpenAI with the assembled prompt
5. Response includes the review brief, per-file risk scores, and retrieved context chunks with file paths and similarity scores
6. Result is cached in Redis and persisted to PostgreSQL; latency breakdown and token usage are logged to the `analysis_runs` table
7. Frontend displays the structured review brief and the retrieved context that grounded it

---

## Local Setup

**Prerequisites:** Java 21, Docker, Node.js 20

### 1. Clone the repository

```bash
git clone https://github.com/yvie97/patchlens.git
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

This starts the Spring Boot backend, PostgreSQL (with pgvector), and Redis in Docker.

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
| POST | `/api/reviews/analyze` | Analyze a GitHub PR by URL |
| POST | `/api/reviews/analyze-sample` | Analyze a built-in sample PR |
| GET | `/api/reviews/{id}` | Fetch a saved review by session ID |
| POST | `/api/repositories/index` | Index repository docs for RAG |

---

## Sample PR Demo

The homepage offers three built-in sample PRs that run the full analysis pipeline without requiring a GitHub token or OpenAI key (in mock mode):

| Sample | Scenario | Expected Risk |
|--------|----------|---------------|
| Redis Session Cache | Replaces in-memory sessions with Redis-backed caching | Medium |
| Auth DB Migration | Migrates auth to OAuth 2.0, drops legacy password columns | High |
| Stripe Checkout | Adds Stripe subscription billing and webhook handler | High |

Each sample exercises different risk scoring rules and produces a different AI review brief.

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
| `retrieval_latency_ms` | Time spent in pgvector top-k retrieval |
| `llm_latency_ms` | Time waiting for the OpenAI API response |
| `total_latency_ms` | End-to-end wall-clock time for the request |
| `prompt_tokens` | Input tokens consumed by the model |
| `completion_tokens` | Output tokens consumed by the model |
| `model_name` | Model used (`gpt-4o-mini`, `mock`, or `cached`) |
| `status` | `success` or `error` |

This makes it straightforward to distinguish whether latency comes from GitHub API fetch, pgvector retrieval, or OpenAI generation — and to verify the cache-hit rate in production.

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

---

## Future Improvements

- GitHub App integration for automatic PR comment posting
- Webhook-triggered analysis on PR creation/update
- Support for private repositories via GitHub OAuth
- Repository-wide indexing and chunk reranking
- Historical analytics on risky PR patterns
