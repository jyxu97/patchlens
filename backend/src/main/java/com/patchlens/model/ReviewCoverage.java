package com.patchlens.model;

import java.util.List;

/**
 * Which changed files the model actually reviewed, and why the others were not. Recorded with
 * every job and shown in the PR comment, so large PRs are never silently partially reviewed.
 *
 * @param truncatedFiles reviewed files whose diff was cut to {@code maxPatchChars} for the prompt
 * @param batches        number of model calls the reviewed files were split into
 */
public record ReviewCoverage(int totalFiles, List<String> reviewedFiles, List<SkippedFile> skippedFiles,
                             List<String> truncatedFiles, int batches, int maxFiles, int maxPatchChars,
                             IndexFreshness indexFreshness) {

    public enum Reason { NO_TEXT_DIFF, REMOVED, FILE_LIMIT, BATCH_LIMIT }

    public record SkippedFile(String file, Reason reason) {}

    public boolean isComplete() { return skippedFiles.isEmpty() && truncatedFiles.isEmpty(); }

    public ReviewCoverage withIndexFreshness(IndexFreshness f) {
        return new ReviewCoverage(totalFiles, reviewedFiles, skippedFiles, truncatedFiles, batches, maxFiles, maxPatchChars, f);
    }
}
