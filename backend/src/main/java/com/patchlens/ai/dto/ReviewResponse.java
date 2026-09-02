package com.patchlens.ai.dto;

import java.util.List;

/** Structured output from {@link com.patchlens.ai.ReviewAiService}. */
public record ReviewResponse(List<ReviewFindingDto> findings) {}
