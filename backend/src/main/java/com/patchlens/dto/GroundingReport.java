package com.patchlens.dto;

import java.util.List;

/**
 * Result of validating the AI-generated review against the actual PR diff.
 * <p>
 * The AI is asked to flag risky files by path. A "grounded" path is one that
 * actually appears in the PR's changed files; a "hallucinated" path does not.
 *
 * @param totalRiskyFiles   total risky-file paths mentioned by the AI
 * @param groundedCount     paths that exist in the actual diff
 * @param hallucinatedCount paths that do not exist in the actual diff
 * @param hallucinatedPaths list of the hallucinated path strings
 * @param groundingRate     groundedCount / totalRiskyFiles (1.0 when totalRiskyFiles == 0)
 */
public record GroundingReport(
        int totalRiskyFiles,
        int groundedCount,
        int hallucinatedCount,
        List<String> hallucinatedPaths,
        double groundingRate
) {}
