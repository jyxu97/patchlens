package com.patchlens.model;

import java.util.List;

/**
 * Risk assessment result for a single changed file.
 */
public record RiskScore(
        String filename,
        int score,
        RiskLevel riskLevel,
        List<String> reasons  // human-readable explanations for why this file was flagged
) {
    public enum RiskLevel { low, medium, high }

    /** Maps a raw score to a risk level using the thresholds from the design doc. */
    public static RiskLevel toLevel(int score) {
        if (score >= 6) return RiskLevel.high;
        if (score >= 3) return RiskLevel.medium;
        return RiskLevel.low;
    }
}