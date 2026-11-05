package com.patchlens.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnifiedDiffTest {

    @TempDir Path root;

    private void write(String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void appliesModificationHunk() throws Exception {
        write("src/A.java", "class A {\n  int x = 1;\n}\n");
        String diff = """
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,3 +1,3 @@
                 class A {
                -  int x = 1;
                +  int x = 2;
                 }
                """;
        UnifiedDiff.apply(root, UnifiedDiff.parse(diff));
        assertThat(Files.readString(root.resolve("src/A.java"))).isEqualTo("class A {\n  int x = 2;\n}\n");
    }

    @Test
    void appliesHunkAtOffsetWhenDeclaredLineIsWrong() throws Exception {
        write("A.java", "// header\n// header2\nclass A {\n  int x = 1;\n}\n");
        String diff = """
                --- a/A.java
                +++ b/A.java
                @@ -1,3 +1,3 @@
                 class A {
                -  int x = 1;
                +  int x = 2;
                 }
                """;
        UnifiedDiff.apply(root, UnifiedDiff.parse(diff));
        assertThat(Files.readString(root.resolve("A.java"))).contains("int x = 2;").startsWith("// header\n");
    }

    @Test
    void createsNewFile() throws Exception {
        String diff = """
                --- /dev/null
                +++ b/src/New.java
                @@ -0,0 +1,2 @@
                +class New {
                +}
                """;
        UnifiedDiff.apply(root, UnifiedDiff.parse(diff));
        assertThat(Files.readString(root.resolve("src/New.java"))).isEqualTo("class New {\n}\n");
    }

    @Test
    void rejectsHunkWhoseContextDoesNotMatch() throws Exception {
        write("A.java", "class A {\n  int y = 1;\n}\n");
        String diff = """
                --- a/A.java
                +++ b/A.java
                @@ -1,3 +1,3 @@
                 class A {
                -  int x = 1;
                +  int x = 2;
                 }
                """;
        assertThatThrownBy(() -> UnifiedDiff.apply(root, UnifiedDiff.parse(diff)))
                .isInstanceOf(UnifiedDiff.PatchException.class)
                .hasMessageContaining("does not match");
        assertThat(Files.readString(root.resolve("A.java"))).contains("int y = 1;");
    }

    @Test
    void rejectsPathTraversal() {
        String diff = """
                --- a/../../etc/passwd
                +++ b/../../etc/passwd
                @@ -1 +1 @@
                -root
                +pwned
                """;
        assertThatThrownBy(() -> UnifiedDiff.apply(root, UnifiedDiff.parse(diff)))
                .isInstanceOf(UnifiedDiff.PatchException.class);
    }

    @Test
    void rejectsTextWithoutFileHeaders() {
        assertThatThrownBy(() -> UnifiedDiff.parse("just change line 3 to return null"))
                .isInstanceOf(UnifiedDiff.PatchException.class)
                .hasMessageContaining("No '---'/'+++' file headers");
    }

    @Test
    void honoursNoNewlineAtEndOfFile() throws Exception {
        write("A.txt", "one\ntwo\n");
        String diff = """
                --- a/A.txt
                +++ b/A.txt
                @@ -1,2 +1,2 @@
                 one
                -two
                +three
                \\ No newline at end of file
                """;
        UnifiedDiff.apply(root, UnifiedDiff.parse(diff));
        assertThat(Files.readString(root.resolve("A.txt"))).isEqualTo("one\nthree");
    }

    @Test
    void toleratesBlankContextLinesWithoutLeadingSpace() throws Exception {
        write("A.java", "a\n\nb\n");
        String diff = "--- a/A.java\n+++ b/A.java\n@@ -1,3 +1,3 @@\n a\n\n-b\n+c\n";
        UnifiedDiff.apply(root, UnifiedDiff.parse(diff));
        assertThat(Files.readString(root.resolve("A.java"))).isEqualTo("a\n\nc\n");
    }
}
