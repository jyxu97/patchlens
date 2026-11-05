package com.patchlens.sandbox;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Snapshot download against a local HTTP server that mimics GitHub's tarball redirect. */
class GitHubSnapshotServiceTest {

    private static final String SHA = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    @TempDir Path tmp;
    private HttpServer server;
    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private GitHubSnapshotService service;

    @BeforeEach
    void start() throws Exception {
        byte[] archive = new TarGzTestArchives()
                .file("acme-backend-" + SHA.substring(0, 7) + "/pom.xml", "<project/>")
                .file("acme-backend-" + SHA.substring(0, 7) + "/src/main/java/A.java", "class A {}")
                .build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/acme/backend/tarball/" + SHA, ex -> {
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getResponseHeaders().add("Location", "/codeload/" + SHA);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/codeload/" + SHA, ex -> {
            downloads.incrementAndGet();
            ex.sendResponseHeaders(200, archive.length);
            ex.getResponseBody().write(archive);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new GitHubSnapshotService(SandboxProperties.forTests(tmp, true), "ghp_testtoken", base);
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void materialisesPinnedRevisionAndCachesArchiveAcrossAttempts() throws Exception {
        RepositorySnapshot snap = service.snapshot("acme", "backend", SHA);
        assertThat(snap.revision()).isEqualTo(SHA);

        Path first = Files.createDirectory(tmp.resolve("attempt1"));
        Path second = Files.createDirectory(tmp.resolve("attempt2"));
        snap.materialize(first);
        Files.writeString(first.resolve("src/main/java/A.java"), "modified by attempt 1");
        snap.materialize(second);

        assertThat(Files.readString(second.resolve("src/main/java/A.java")))
                .as("each attempt gets a fresh, unmodified tree").isEqualTo("class A {}");
        assertThat(second.resolve("pom.xml")).exists();
        assertThat(downloads.get()).as("immutable SHA archive is downloaded once").isEqualTo(1);
        assertThat(authHeader.get()).isEqualTo("Bearer ghp_testtoken");
    }

    @Test
    void rejectsNonPinnedRevisionsAndUnsafeNames() {
        assertThatThrownBy(() -> service.snapshot("acme", "backend", "main"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("40-char");
        assertThatThrownBy(() -> service.snapshot("acme", "../etc", SHA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downloadFailureSurfacesAsIOException() {
        RepositorySnapshot snap = service.snapshot("acme", "missing", SHA);
        assertThatThrownBy(() -> snap.materialize(Files.createDirectory(tmp.resolve("x"))))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP 404");
    }
}
