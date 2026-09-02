package com.patchlens.ai.dto;

import com.patchlens.model.FindingCategory;
import com.patchlens.model.FindingSeverity;

import java.util.List;

/**
 * Structured finding returned by {@link com.patchlens.ai.ReviewAiService}.
 * Separate from the JPA entity {@link com.patchlens.model.ReviewFinding}:
 * this is the raw AI output before persistence and filter validation.
 */
public record ReviewFindingDto(
        String file,
        int startLine,
        int endLine,
        FindingSeverity severity,
        FindingCategory category,
        String title,
        String explanation,
        List<EvidenceRef> evidence,
        double confidence
) {}
