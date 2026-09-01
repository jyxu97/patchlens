package com.patchlens.model;

public enum JobStatus {
    PENDING,
    PROCESSING,
    GENERATING_FINDINGS,
    GENERATING_PATCHES,
    VALIDATING_PATCHES,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}
