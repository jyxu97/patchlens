// TypeScript interfaces mirroring the Spring Boot backend JSON responses

export type RiskLevel = 'low' | 'medium' | 'high'

export interface RiskScore {
  filename: string
  score: number
  riskLevel: RiskLevel
  reasons: string[]
}

export interface RiskyFile {
  path: string
  riskLevel: RiskLevel
  reason: string
}

export interface ReviewResult {
  summary: {
    title: string
    overview: string
    mainChanges: string[]
  }
  riskAssessment: {
    overallRisk: RiskLevel
    riskyFiles: RiskyFile[]
  }
  suggestedTests: string[]
  reviewChecklist: string[]
}

// Response from POST /api/reviews/analyze or /analyze-sample
// Some fields are absent on cache-hit or when fetching a saved session by ID
export interface AnalyzeResponse {
  reviewSessionId?: string
  repository?: string
  pullRequestNumber?: number
  title?: string
  diffHash?: string
  cacheHit?: boolean
  overallRisk?: RiskLevel
  riskScores?: RiskScore[]
  result: ReviewResult
  mode?: string
  createdAt?: string
}
