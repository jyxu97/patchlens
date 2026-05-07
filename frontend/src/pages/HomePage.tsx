import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { analyzePr, analyzeSample, getMetrics } from '../api'
import type { MetricsResponse } from '../types'

const SAMPLES = [
  {
    id: 'redis-session-cache',
    title: 'Redis Session Cache',
    description: 'Replaces in-memory sessions with a Redis-backed cache layer',
    risk: 'Medium',
    riskColor: 'bg-yellow-100 text-yellow-800',
  },
  {
    id: 'db-migration',
    title: 'Auth DB Migration',
    description: 'Migrates auth to OAuth 2.0, drops legacy password columns',
    risk: 'High',
    riskColor: 'bg-red-100 text-red-800',
  },
  {
    id: 'stripe-checkout',
    title: 'Stripe Checkout',
    description: 'Adds Stripe subscription billing and webhook handler',
    risk: 'High',
    riskColor: 'bg-red-100 text-red-800',
  },
]

export default function HomePage() {
  const [url, setUrl] = useState('')
  const [loading, setLoading] = useState(false)
  const [loadingSampleId, setLoadingSampleId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [metrics, setMetrics] = useState<MetricsResponse | null>(null)
  const navigate = useNavigate()

  useEffect(() => {
    getMetrics().then(setMetrics).catch(() => {})
  }, [])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const data = await analyzePr(url)
      navigate(`/result/${data.reviewSessionId}`, { state: { data } })
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setLoading(false)
    }
  }

  async function handleSample(sampleId: string) {
    setError(null)
    setLoadingSampleId(sampleId)
    try {
      const data = await analyzeSample(sampleId)
      navigate(`/result/${data.reviewSessionId}`, { state: { data } })
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setLoadingSampleId(null)
    }
  }

  const busy = loading || loadingSampleId !== null

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center px-4">
      <div className="w-full max-w-lg">
        {/* Hero */}
        <div className="mb-8">
          <h1 className="text-4xl font-bold text-gray-900">PatchLens</h1>
          <p className="text-gray-500 mt-2">AI-powered pull request review assistant</p>
          {metrics && (
            <div className="flex gap-4 mt-3 text-xs text-gray-400 flex-wrap">
              <span><span className="text-gray-600 font-medium">{metrics.totalAnalyses}</span> analyses</span>
              <span>·</span>
              <span><span className="text-gray-600 font-medium">{Math.round(metrics.cacheHitRate * 100)}%</span> cache hit rate</span>
              <span>·</span>
              <span>avg <span className="text-gray-600 font-medium">{(metrics.avgCacheMissLatencyMs / 1000).toFixed(1)}s</span> → <span className="text-gray-600 font-medium">{(metrics.avgCacheHitLatencyMs / 1000).toFixed(1)}s</span> cached</span>
            </div>
          )}
        </div>

        {/* Input card */}
        <form
          onSubmit={handleSubmit}
          className="bg-white rounded-xl shadow-sm border border-gray-200 p-6"
        >
          <label className="block text-sm font-medium text-gray-700 mb-2">
            GitHub Pull Request URL
          </label>
          <input
            type="url"
            value={url}
            onChange={e => setUrl(e.target.value)}
            placeholder="https://github.com/owner/repo/pull/123"
            required
            className="w-full border border-gray-300 rounded-lg px-4 py-2.5 text-sm
                       focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          {error && (
            <p className="mt-2 text-sm text-red-600">{error}</p>
          )}
          <button
            type="submit"
            disabled={busy}
            className="mt-4 w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400
                       text-white font-medium py-2.5 rounded-lg text-sm transition-colors"
          >
            {loading ? 'Analyzing…' : 'Analyze PR'}
          </button>
        </form>

        {/* Divider */}
        <div className="flex items-center gap-3 my-4">
          <div className="flex-1 h-px bg-gray-200" />
          <span className="text-xs text-gray-400">or try a sample</span>
          <div className="flex-1 h-px bg-gray-200" />
        </div>

        {/* Sample cards */}
        <div className="grid grid-cols-3 gap-3">
          {SAMPLES.map(sample => (
            <button
              key={sample.id}
              onClick={() => handleSample(sample.id)}
              disabled={busy}
              className="bg-white border border-gray-200 rounded-xl p-4 text-left shadow-sm
                         hover:border-blue-300 hover:shadow-md disabled:opacity-50
                         transition-all flex flex-col gap-2"
            >
              <div className="flex items-start justify-between gap-1">
                <span className="text-xs font-semibold text-gray-800 leading-snug">
                  {loadingSampleId === sample.id ? 'Loading…' : sample.title}
                </span>
                <span className={`shrink-0 text-xs font-medium px-1.5 py-0.5 rounded-full ${sample.riskColor}`}>
                  {sample.risk}
                </span>
              </div>
              <p className="text-xs text-gray-500 leading-snug">{sample.description}</p>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
