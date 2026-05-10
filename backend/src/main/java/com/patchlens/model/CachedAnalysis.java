package com.patchlens.model;

import java.util.List;

/**
 * The complete analysis result stored in Redis.
 * Includes the AI-generated review plus risk scores and retrieved context
 * so that cache hits return the same complete response as cache misses.
 */
public record CachedAnalysis(
        ReviewResult reviewResult,
        RiskScore.RiskLevel overallRisk,
        List<RiskScore> riskScores,
        List<RetrievedContextChunk> retrievedContext
) {}
