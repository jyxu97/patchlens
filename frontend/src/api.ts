import type { AnalyzeResponse } from './types'

const BASE_URL = 'http://localhost:8080'

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

// GET /api/reviews/{sessionId} — fetch a previously saved review
export async function getReview(sessionId: string): Promise<AnalyzeResponse> {
  const res = await fetch(`${BASE_URL}/api/reviews/${sessionId}`)
  if (!res.ok) throw new Error('Review not found')
  return res.json()
}
