package com.patchlens.sandbox;

import java.nio.file.Path;

/**
 * Path-safety helpers for handling untrusted, repository-relative paths
 * (from PR diffs, model-generated patches, and repository archives).
 */
public final class SafePaths {

    private SafePaths() {}

    /**
     * Returns true if {@code rel} is a plain relative path that cannot escape its root:
     * no absolute paths, no {@code ..} segments, no backslashes, no NUL bytes, no home expansion.
     */
    public static boolean isSafeRelative(String rel) {
        if (rel == null || rel.isBlank()) return false;
        if (rel.indexOf('\0') >= 0 || rel.indexOf('\\') >= 0) return false;
        if (rel.startsWith("/") || rel.startsWith("~")) return false;
        if (rel.length() > 1 && rel.charAt(1) == ':') return false; // Windows drive letter
        for (String segment : rel.split("/", -1)) {
            if (segment.equals("..")) return false;
        }
        return true;
    }

    /**
     * Resolves {@code rel} under {@code root}, guaranteeing the result stays inside {@code root}.
     *
     * @throws IllegalArgumentException if the path is unsafe or escapes the root
     */
    public static Path resolveInside(Path root, String rel) {
        if (!isSafeRelative(rel)) {
            throw new IllegalArgumentException("Unsafe path: " + rel);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(rel).normalize();
        if (!resolved.startsWith(normalizedRoot) || resolved.equals(normalizedRoot)) {
            throw new IllegalArgumentException("Path escapes sandbox root: " + rel);
        }
        return resolved;
    }
}
