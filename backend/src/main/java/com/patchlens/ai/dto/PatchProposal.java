package com.patchlens.ai.dto;

/**
 * Structured patch output from {@link com.patchlens.ai.PatchAiService}
 * and {@link com.patchlens.ai.RepairAiService}.
 */
public record PatchProposal(
        String targetFile,
        String unifiedDiff,
        String rationale,
        String expectedBehavior
) {}
