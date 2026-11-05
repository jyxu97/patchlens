package com.patchlens.model;

import java.time.Instant;

/**
 * How current the repository context used for an analysis was. Reported with every review so
 * stale or unknown context is explicit rather than silent.
 *
 * @param status          FRESH (index built from the current default-branch tree), STALE (an
 *                        older tree; a re-index was triggered), UNKNOWN (current tree could not be
 *                        determined, or the index predates version tracking), NOT_INDEXED
 * @param indexedRevision tree SHA of the index used for retrieval (null if none)
 * @param currentRevision current default-branch tree SHA (null if unknown)
 */
public record IndexFreshness(Status status, String indexedRevision, String currentRevision,
                             Instant indexedAt, boolean partial, boolean reindexTriggered) {

    public enum Status { FRESH, STALE, UNKNOWN, NOT_INDEXED }

    public IndexFreshness withReindexTriggered() {
        return new IndexFreshness(status, indexedRevision, currentRevision, indexedAt, partial, true);
    }

    public static IndexFreshness notIndexed(String currentRevision) {
        return new IndexFreshness(Status.NOT_INDEXED, null, currentRevision, null, false, false);
    }
}
