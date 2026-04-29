import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { analyzePr, analyzeSample } from '../api'

export default function HomePage() {
  const [url, setUrl] = useState('')
  const [loading, setLoading] = useState(false)
  const [sampleLoading, setSampleLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()

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

  async function handleSample() {
    setError(null)
    setSampleLoading(true)
    try {
      const data = await analyzeSample('redis-session-cache')
      navigate(`/result/${data.reviewSessionId}`, { state: { data } })
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setSampleLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center px-4">
      <div className="w-full max-w-lg">
        {/* Hero */}
        <div className="mb-8">
          <h1 className="text-4xl font-bold text-gray-900">PatchLens</h1>
          <p className="text-gray-500 mt-2">AI-powered pull request review assistant</p>
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
            disabled={loading || sampleLoading}
            className="mt-4 w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400
                       text-white font-medium py-2.5 rounded-lg text-sm transition-colors"
          >
            {loading ? 'Analyzing…' : 'Analyze PR'}
          </button>
        </form>

        {/* Divider */}
        <div className="flex items-center gap-3 my-4">
          <div className="flex-1 h-px bg-gray-200" />
          <span className="text-xs text-gray-400">or</span>
          <div className="flex-1 h-px bg-gray-200" />
        </div>

        {/* Sample button */}
        <button
          onClick={handleSample}
          disabled={loading || sampleLoading}
          className="w-full bg-white hover:bg-gray-50 disabled:bg-gray-100
                     border border-gray-200 text-gray-700 font-medium py-2.5
                     rounded-xl text-sm transition-colors shadow-sm"
        >
          {sampleLoading ? 'Loading sample…' : '✦ Try Sample PR — no GitHub token needed'}
        </button>
      </div>
    </div>
  )
}
