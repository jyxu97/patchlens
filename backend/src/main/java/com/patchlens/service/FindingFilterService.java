package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic post-processing filter applied after LLM finding detection.
 * Eliminates malformed, out-of-range, and duplicate findings.
 */
@Service
public class FindingFilterService {

    private static final double MIN_CONFIDENCE = 0.3;
    private static final int MAX_FINDINGS_PER_PR = 10;

    /**
     * Filters a raw list of detected findings against the actual PR files.
     *
     * Rules applied (in order):
     * 1. Reject if required fields are null/blank
     * 2. Reject if file path is not in the PR's changed files list
     * 3. Reject if line numbers are out of range (negative or inverted)
     * 4. Reject if confidence < 0.3
     * 5. Deduplicate overlapping findings (same file, overlapping line ranges)
     * 6. Cap at 10 findings per PR
     */
    public List<OpenAIService.DetectedFinding> filter(List<OpenAIService.DetectedFinding> findings,
                                                      List<ChangedFile> changedFiles) {
        Set<String> allowedFiles = changedFiles.stream()
                .map(ChangedFile::filename)
                .collect(Collectors.toSet());

        List<OpenAIService.DetectedFinding> accepted = new ArrayList<>();

        for (OpenAIService.DetectedFinding f : findings) {
            if (!isValid(f, allowedFiles)) continue;
            if (!isNonOverlapping(f, accepted)) continue;
            accepted.add(f);
            if (accepted.size() >= MAX_FINDINGS_PER_PR) break;
        }

        return accepted;
    }

    private boolean isValid(OpenAIService.DetectedFinding f, Set<String> allowedFiles) {
        // Required fields
        if (f.file() == null || f.file().isBlank()) return false;
        if (f.title() == null || f.title().isBlank()) return false;
        if (f.explanation() == null || f.explanation().isBlank()) return false;
        if (f.category() == null) return false;
        if (f.severity() == null) return false;

        // File must be in PR
        if (!allowedFiles.contains(f.file())) return false;

        // Line range sanity
        if (f.startLine() < 0 || f.endLine() < 0) return false;
        if (f.endLine() > 0 && f.startLine() > f.endLine()) return false;

        // Confidence threshold
        if (f.confidence() < MIN_CONFIDENCE) return false;

        return true;
    }

    /**
     * Returns true if the finding does not overlap with any already-accepted finding
     * on the same file (same file, overlapping line range = duplicate).
     */
    private boolean isNonOverlapping(OpenAIService.DetectedFinding candidate,
                                     List<OpenAIService.DetectedFinding> accepted) {
        for (OpenAIService.DetectedFinding existing : accepted) {
            if (!existing.file().equals(candidate.file())) continue;
            // Overlap: the ranges intersect
            int existStart = existing.startLine();
            int existEnd   = existing.endLine() > 0 ? existing.endLine() : existStart;
            int candStart  = candidate.startLine();
            int candEnd    = candidate.endLine() > 0 ? candidate.endLine() : candStart;
            if (candStart <= existEnd && candEnd >= existStart) {
                return false;
            }
        }
        return true;
    }
}
