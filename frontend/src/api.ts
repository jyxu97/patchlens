import type { AnalyzeResponse, MetricsResponse } from './types'

// In production, set VITE_API_BASE_URL to the backend's public URL (e.g. Lightsail IP)
// In local dev, falls back to localhost:8080
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

// POST /api/reviews/analyze — analyze a real GitHub PR by URL
export async function analyzePr(pullRequestUrl: string): Promise<AnalyzeResponse> {
  const res = await fetch(`${BASE_URL}/api/reviews/analyze`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pullRequestUrl }),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.message ?? `Request failed (${res.status})`)
  }
  return res.json()
}

// POST /api/reviews/analyze-sample — analyze a built-in sample PR (no GitHub token needed)
export async function analyzeSample(sampleId: string): Promise<AnalyzeResponse> {
  const res = await fetch(`${BASE_URL}/api/reviews/analyze-sample`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sampleId }),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.message ?? `Request failed (${res.status})`)
  }
  return res.json()
}

// GET /api/metrics — aggregated stats from analysis_runs
export async function getMetrics(): Promise<MetricsResponse> {
  const res = await fetch(`${BASE_URL}/api/metrics`)
  if (!res.ok) throw new Error('Failed to fetch metrics')
  return res.json()
}

// GET /api/reviews/{sessionId} — fetch a previously saved review
export async function getReview(sessionId: string): Promise<AnalyzeResponse> {
  const res = await fetch(`${BASE_URL}/api/reviews/${sessionId}`)
  if (!res.ok) throw new Error('Review not found')
  return res.json()
}
