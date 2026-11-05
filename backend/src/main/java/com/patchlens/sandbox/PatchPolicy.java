package com.patchlens.sandbox;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic policy checks applied to every model-generated patch <em>before</em> sandbox
 * execution. Passing policy only means the patch is well formed and in scope; it never means
 * the patch is correct (see {@code ValidationStatus.VALIDATED}).
 */
public final class PatchPolicy {

    public static final int MAX_PATCH_LINES = 200;

    /** Paths a generated patch may never touch, regardless of the finding. */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            ".github/", ".mvn/", ".git/", ".gitlab-ci", ".circleci/");
    private static final Set<String> FORBIDDEN_FILES = Set.of(
            "patchlens.yaml", "patchlens.yml", ".patchlens.yaml", ".patchlens.yml",
            "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat", "Dockerfile");

    private PatchPolicy() {}

    /**
     * @param patchText    model-generated unified diff
     * @param allowedFiles repository-relative paths the patch may modify
     * @return empty if the patch passes policy, otherwise a human/model-readable reason
     */
    public static Optional<String> check(String patchText, Set<String> allowedFiles) {
        if (patchText == null || patchText.isBlank()) return Optional.of("Patch is empty");

        long lineCount = patchText.lines().count();
        if (lineCount > MAX_PATCH_LINES) {
            return Optional.of("Patch exceeds " + MAX_PATCH_LINES + " lines (" + lineCount + " lines)");
        }

        List<UnifiedDiff.FilePatch> files;
        try {
            files = UnifiedDiff.parse(patchText);
        } catch (UnifiedDiff.PatchException e) {
            return Optional.of("Invalid unified diff: " + e.getMessage());
        }

        for (UnifiedDiff.FilePatch fp : files) {
            for (String p : List.of(fp.oldPath(), fp.newPath())) {
                if (UnifiedDiff.DEV_NULL.equals(p)) continue;
                if (!SafePaths.isSafeRelative(p)) return Optional.of("Unsafe path in patch: " + p);
            }
            if (fp.isRename())   return Optional.of("Renames are not allowed: " + fp.oldPath() + " -> " + fp.newPath());
            if (fp.isDeletion()) return Optional.of("Deleting files is not allowed: " + fp.oldPath());

            String target = fp.targetPath();
            String fileName = target.substring(target.lastIndexOf('/') + 1);
            if (FORBIDDEN_FILES.contains(fileName) || FORBIDDEN_PREFIXES.stream().anyMatch(target::startsWith)) {
                return Optional.of("Patch modifies a protected build/CI file: " + target);
            }
            if (!allowedFiles.contains(target)) {
                return Optional.of("Patch modifies a file outside the allowed set: " + target
                        + " (allowed: " + String.join(", ", allowedFiles) + ")");
            }
        }
        return Optional.empty();
    }
}
