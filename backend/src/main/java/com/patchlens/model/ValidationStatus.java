package com.patchlens.model;

/**
 * Outcome of validating a generated patch.
 *
 * <p><b>Invariant:</b> {@link #VALIDATED} means the patch applied to the pinned repository
 * snapshot and every configured check that actually ran in the sandbox (compile, optional
 * static analysis, tests) passed. Policy/format checks alone never produce {@code VALIDATED}.
 */
public enum ValidationStatus {
    /** Applied, compiled and passed all configured checks in the sandbox. */
    VALIDATED,
    REJECTED_PATCH_APPLY,
    REJECTED_COMPILE,
    REJECTED_STATIC_ANALYSIS,
    REJECTED_TEST,
    /** Failed deterministic policy (invalid diff, disallowed file, size). */
    REJECTED_POLICY,
    /** Sandbox checks did not run (sandbox disabled or unavailable) — correctness unknown. */
    NOT_VALIDATED,
    /** Validation could not complete for infrastructure reasons (snapshot, dependencies, Docker) — correctness unknown. */
    VALIDATION_ERROR,
    PENDING;

    /** True for failures caused by the patch itself, which a repair attempt may fix. */
    public boolean isRepairable() {
        return switch (this) {
            case REJECTED_PATCH_APPLY, REJECTED_COMPILE, REJECTED_STATIC_ANALYSIS,
                 REJECTED_TEST, REJECTED_POLICY -> true;
            default -> false;
        };
    }
}
