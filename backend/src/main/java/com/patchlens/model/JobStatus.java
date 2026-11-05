package com.patchlens.model;

public enum JobStatus {
    PENDING,
    PROCESSING,
    GENERATING_FINDINGS,
    GENERATING_PATCHES,
    VALIDATING_PATCHES,
    COMPLETED,
    FAILED,
    DEAD_LETTER,
    /** A newer commit was pushed while this job ran; the newer commit's job supersedes it. */
    SUPERSEDED
}
