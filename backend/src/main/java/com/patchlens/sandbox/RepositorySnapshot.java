package com.patchlens.sandbox;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An immutable source tree at a fixed revision that can be materialised into a fresh,
 * empty workspace. Every validation attempt (initial or repaired patch) materialises its own
 * copy, so no attempt ever sees a workspace modified by a previous one.
 */
public interface RepositorySnapshot {

    /** Stable identifier of the revision (commit SHA, or fixture id + dataset version). */
    String revision();

    /** Human-readable description for logs and persisted validation records. */
    String describe();

    /** Key identifying the dependency cache this snapshot may share (e.g. "owner/repo"). */
    String dependencyCacheKey();

    /** Writes the full tree into {@code emptyDir}, preserving the project directory structure. */
    void materialize(Path emptyDir) throws IOException;
}
