package com.patchlens.service;

import com.patchlens.dto.GroundingReport;
import com.patchlens.model.ChangedFile;
import com.patchlens.model.ReviewResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks whether the file paths mentioned in the AI-generated review actually
 * appear in the PR diff.  A path that the AI invented (not present in the
 * changed-files list) is counted as a hallucinated reference.
 */
@Service
public class GroundingValidationService {

    /**
     * Validates risky-file paths in {@code reviewResult} against the actual
     * {@code changedFiles} list.
     *
     * @param reviewResult the AI-generated review brief
     * @param changedFiles the files that were actually changed in the PR
     * @return a {@link GroundingReport} with grounding rate and hallucinated paths
     */
    public GroundingReport validate(ReviewResult reviewResult, List<ChangedFile> changedFiles) {
        List<ReviewResult.RiskyFile> riskyFiles = reviewResult.riskAssessment().riskyFiles();

        if (riskyFiles == null || riskyFiles.isEmpty()) {
            return new GroundingReport(0, 0, 0, List.of(), 1.0);
        }

        Set<String> actualPaths = changedFiles.stream()
                .map(ChangedFile::filename)
                .collect(Collectors.toSet());

        List<String> hallucinated = riskyFiles.stream()
                .map(ReviewResult.RiskyFile::path)
                .filter(path -> path != null && !actualPaths.contains(path))
                .distinct()
                .toList();

        int total = riskyFiles.size();
        int hallucinatedCount = hallucinated.size();
        int groundedCount = total - hallucinatedCount;
        double rate = (double) groundedCount / total;

        return new GroundingReport(total, groundedCount, hallucinatedCount, hallucinated, rate);
    }
}
