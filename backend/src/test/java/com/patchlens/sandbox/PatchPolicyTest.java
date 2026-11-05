package com.patchlens.sandbox;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PatchPolicyTest {

    private static final Set<String> ALLOWED = Set.of("src/main/java/A.java");

    private static String diffFor(String path) {
        return "--- a/" + path + "\n+++ b/" + path + "\n@@ -1 +1 @@\n-old\n+new\n";
    }

    @Test
    void acceptsWellFormedPatchToAllowedFile() {
        assertThat(PatchPolicy.check(diffFor("src/main/java/A.java"), ALLOWED)).isEmpty();
    }

    @Test
    void rejectsInvalidUnifiedDiff() {
        assertThat(PatchPolicy.check("Replace the null check with Objects.requireNonNull", ALLOWED))
                .hasValueSatisfying(r -> assertThat(r).startsWith("Invalid unified diff"));
    }

    @Test
    void rejectsDiffThatStartsWithHeadersButHasNoHunks() {
        assertThat(PatchPolicy.check("--- a/src/main/java/A.java\n+++ b/src/main/java/A.java\n", ALLOWED))
                .hasValueSatisfying(r -> assertThat(r).contains("no hunks"));
    }

    @Test
    void rejectsDisallowedFile() {
        assertThat(PatchPolicy.check(diffFor("src/main/java/Other.java"), ALLOWED))
                .hasValueSatisfying(r -> assertThat(r).contains("outside the allowed set"));
    }

    @Test
    void rejectsProtectedBuildFilesEvenIfAllowed() {
        assertThat(PatchPolicy.check(diffFor(".github/workflows/ci.yml"), Set.of(".github/workflows/ci.yml")))
                .hasValueSatisfying(r -> assertThat(r).contains("protected"));
        assertThat(PatchPolicy.check(diffFor("patchlens.yaml"), Set.of("patchlens.yaml")))
                .hasValueSatisfying(r -> assertThat(r).contains("protected"));
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThat(PatchPolicy.check(diffFor("../outside.java"), Set.of("../outside.java"))).isPresent();
        assertThat(PatchPolicy.check("--- /etc/passwd\n+++ /etc/passwd\n@@ -1 +1 @@\n-a\n+b\n", ALLOWED)).isPresent();
    }

    @Test
    void rejectsDeletionsAndOversizedPatches() {
        String delete = "--- a/src/main/java/A.java\n+++ /dev/null\n@@ -1 +0,0 @@\n-old\n";
        assertThat(PatchPolicy.check(delete, ALLOWED)).hasValueSatisfying(r -> assertThat(r).contains("Deleting"));

        String huge = diffFor("src/main/java/A.java") + "+x\n".repeat(PatchPolicy.MAX_PATCH_LINES);
        assertThat(PatchPolicy.check(huge, ALLOWED)).hasValueSatisfying(r -> assertThat(r).contains("exceeds"));
    }
}
