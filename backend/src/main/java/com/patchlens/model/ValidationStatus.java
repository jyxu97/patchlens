package com.patchlens.model;

public enum ValidationStatus {
    VALIDATED,
    REJECTED_PATCH_APPLY,
    REJECTED_COMPILE,
    REJECTED_STATIC_ANALYSIS,
    REJECTED_TEST,
    REJECTED_POLICY,
    PENDING
}
