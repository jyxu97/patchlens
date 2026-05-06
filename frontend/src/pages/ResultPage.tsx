import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { getReview } from '../api'
import type { AnalyzeResponse, RetrievedChunk, RiskLevel } from '../types'

// Badge color for each risk level
const riskBadge: Record<RiskLevel, string> = {
  low: 'bg-green-100 text-green-800',
  medium: 'bg-yellow-100 text-yellow-800',
  high: 'bg-red-100 text-red-800',
}

export default function ResultPage() {
  const { sessionId } = useParams<{ sessionId: string }>()
  const location = useLocation()

  // If we navigated here from HomePage, data is already in router state
  const [data, setData] = useState<AnalyzeResponse | null>(
    (location.state as { data?: AnalyzeResponse })?.data ?? null
  )
  const [error, setError] = useState<string | null>(null)

  // If the page is opened directly by URL (e.g. shared link), fetch from backend
  useEffect(() => {
    if (!data && sessionId) {
      getReview(sessionId)
        .then(setData)
        .catch(err => setError(err instanceof Error ? err.message : 'Failed to load'))
    }
  }, [sessionId, data])

  if (error) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <p className="text-red-600">{error}</p>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <p className="text-gray-400">Loading…</p>
      </div>
    )
  }

  const { result, riskScores, cacheHit, retrievedContext } = data
  // Fallback: title from summary, overallRisk from riskAssessment
  const title = data.title ?? result.summary.title
  const overallRisk = data.overallRisk ?? result.riskAssessment.overallRisk

  return (
    <div className="min-h-screen bg-gray-50 py-10 px-4">
      <div className="max-w-3xl mx-auto space-y-5">

        {/* Header */}
        <div className="flex items-start justify-between gap-4">
          <div>
            <Link to="/" className="text-sm text-blue-600 hover:underline">
              ← New Review
            </Link>
            <h1 className="text-2xl font-bold text-gray-900 mt-1">{title}</h1>
            {data.repository && (
              <p className="text-sm text-gray-500 mt-0.5">{data.repository}</p>
            )}
          </div>
          <div className="flex flex-col items-end gap-1.5 shrink-0">
            <span className={`text-xs font-semibold px-3 py-1 rounded-full ${riskBadge[overallRisk]}`}>
              {overallRisk.toUpperCase()} RISK
            </span>
            {cacheHit && (
              <span className="text-xs text-gray-400">cached result</span>
            )}
          </div>
        </div>

        {/* Summary */}
        <section className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-900 mb-3">Summary</h2>
          <p className="text-sm text-gray-700 mb-4">{result.summary.overview}</p>
          <ul className="space-y-1.5">
            {result.summary.mainChanges.map((change, i) => (
              <li key={i} className="text-sm text-gray-700 flex gap-2">
                <span className="text-gray-400 shrink-0">•</span>
                {change}
              </li>
            ))}
          </ul>
        </section>

        {/* Rule-based risk scores per file */}
        {riskScores && riskScores.length > 0 && (
          <section className="bg-white rounded-xl border border-gray-200 p-6">
            <h2 className="font-semibold text-gray-900 mb-3">File Risk Scores</h2>
            <div className="space-y-3">
              {riskScores.map((rs, i) => (
                <div key={i} className="flex items-start justify-between gap-4">
                  <span className="text-sm font-mono text-gray-700 break-all">
                    {rs.filename}
                  </span>
                  <div className="flex flex-col items-end gap-1 shrink-0">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${riskBadge[rs.riskLevel]}`}>
                      {rs.riskLevel}
                    </span>
                    {rs.reasons.length > 0 && (
                      <span className="text-xs text-gray-400">{rs.reasons.join(', ')}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}

        {/* AI risk assessment per file */}
        {result.riskAssessment.riskyFiles.length > 0 && (
          <section className="bg-white rounded-xl border border-gray-200 p-6">
            <h2 className="font-semibold text-gray-900 mb-3">AI Risk Assessment</h2>
            <div className="space-y-3">
              {result.riskAssessment.riskyFiles.map((f, i) => (
                <div key={i} className="flex items-start justify-between gap-4">
                  <div>
                    <p className="text-sm font-mono text-gray-700 break-all">{f.path}</p>
                    <p className="text-xs text-gray-500 mt-0.5">{f.reason}</p>
                  </div>
                  <span className={`text-xs font-semibold px-2 py-0.5 rounded-full shrink-0 ${riskBadge[f.riskLevel]}`}>
                    {f.riskLevel}
                  </span>
                </div>
              ))}
            </div>
          </section>
        )}

        {/* Suggested Tests */}
        <section className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-900 mb-3">Suggested Tests</h2>
          <ul className="space-y-1.5">
            {result.suggestedTests.map((t, i) => (
              <li key={i} className="text-sm text-gray-700 flex gap-2">
                <span className="text-blue-500 shrink-0">✓</span>
                {t}
              </li>
            ))}
          </ul>
        </section>

        {/* Review Checklist */}
        <section className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-900 mb-3">Review Checklist</h2>
          <ul className="space-y-1.5">
            {result.reviewChecklist.map((item, i) => (
              <li key={i} className="text-sm text-gray-700 flex gap-2">
                <span className="text-gray-400 shrink-0">□</span>
                {item}
              </li>
            ))}
          </ul>
        </section>

        {/* Retrieved Context */}
        {retrievedContext && retrievedContext.length > 0 && (
          <section className="bg-white rounded-xl border border-gray-200 p-6">
            <h2 className="font-semibold text-gray-900 mb-1">Retrieved Context</h2>
            <p className="text-xs text-gray-400 mb-4">
              Repository chunks retrieved via pgvector to ground the AI review
            </p>
            <div className="space-y-4">
              {retrievedContext.map((chunk: RetrievedChunk, i: number) => (
                <div key={i} className="border border-gray-100 rounded-lg p-3">
                  <div className="flex items-center justify-between gap-3 mb-2">
                    <span className="text-xs font-mono text-gray-700 break-all">
                      {chunk.filePath}
                    </span>
                    <span className="shrink-0 text-xs font-medium text-blue-600 bg-blue-50 px-2 py-0.5 rounded-full">
                      {(chunk.similarityScore * 100).toFixed(1)}% match
                    </span>
                  </div>
                  <p className="text-xs text-gray-500 font-mono leading-relaxed whitespace-pre-wrap">
                    {chunk.contentPreview}
                  </p>
                </div>
              ))}
            </div>
          </section>
        )}

      </div>
    </div>
  )
}
