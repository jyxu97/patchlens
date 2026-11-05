package com.patchlens.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TarGzExtractorTest {

    @TempDir Path dest;

    private TarGzExtractor.Result extract(byte[] archive) throws IOException {
        return TarGzExtractor.extract(new ByteArrayInputStream(archive), dest, TarGzExtractor.Limits.defaults());
    }

    @Test
    void stripsGitHubRootDirectoryAndPreservesStructure() throws Exception {
        byte[] a = new TarGzTestArchives()
                .dir("acme-repo-abc123/")
                .file("acme-repo-abc123/pom.xml", "<project/>")
                .file("acme-repo-abc123/src/main/java/demo/A.java", "class A {}")
                .build();
        TarGzExtractor.Result r = extract(a);

        assertThat(r.filesWritten()).isEqualTo(2);
        assertThat(Files.readString(dest.resolve("pom.xml"))).isEqualTo("<project/>");
        assertThat(Files.readString(dest.resolve("src/main/java/demo/A.java"))).isEqualTo("class A {}");
        assertThat(dest.resolve("acme-repo-abc123")).doesNotExist();
    }

    @Test
    void usesPaxPathForLongNames() throws Exception {
        String longPath = "acme-repo-abc123/" + "deep/".repeat(30) + "File.java";
        extract(new TarGzTestArchives().paxFile(longPath, "long").build());
        assertThat(Files.readString(dest.resolve("deep/".repeat(30) + "File.java"))).isEqualTo("long");
    }

    @Test
    void skipsSymlinksInsteadOfMaterialisingThem() throws Exception {
        byte[] a = new TarGzTestArchives()
                .symlink("root/evil-link", "/etc/passwd")
                .file("root/ok.txt", "ok")
                .build();
        TarGzExtractor.Result r = extract(a);

        assertThat(r.skippedLinks()).isEqualTo(1);
        assertThat(dest.resolve("evil-link")).doesNotExist();
        assertThat(Files.readString(dest.resolve("ok.txt"))).isEqualTo("ok");
    }

    @Test
    void rejectsPathTraversalEntries() throws Exception {
        byte[] a = new TarGzTestArchives().file("root/../../escaped.txt", "x").build();
        assertThatThrownBy(() -> extract(a)).isInstanceOf(IOException.class).hasMessageContaining("Unsafe path");
        assertThat(dest.getParent().resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void enforcesSizeLimit() throws Exception {
        byte[] a = new TarGzTestArchives().file("root/big.bin", "x".repeat(5000)).build();
        assertThatThrownBy(() -> TarGzExtractor.extract(new ByteArrayInputStream(a), dest,
                new TarGzExtractor.Limits(1000, 10)))
                .isInstanceOf(IOException.class).hasMessageContaining("exceeds");
    }
}
