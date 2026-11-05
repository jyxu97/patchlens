package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubChangedFilesPaginationTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int totalFiles;
    private GitHubService github;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/pulls/1/files", ex -> {
            requests.incrementAndGet();
            String q = ex.getRequestURI().getQuery();
            int page = Integer.parseInt(q.replaceAll(".*page=(\\d+).*", "$1"));
            int per = Integer.parseInt(q.replaceAll(".*per_page=(\\d+).*", "$1"));
            StringBuilder sb = new StringBuilder("[");
            for (int i = (page - 1) * per; i < Math.min(page * per, totalFiles); i++) {
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"filename\":\"f").append(i).append(".java\",\"status\":\"modified\",\"additions\":1,\"deletions\":0,\"changes\":1,\"patch\":\"@@ -1 +1 @@\"}");
            }
            byte[] b = sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        github = new GitHubService(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void followsPaginationBeyondTheFirstHundredFiles() {
        totalFiles = 250;
        List<ChangedFile> files = github.fetchChangedFiles("o", "r", 1);
        assertThat(files).hasSize(250);
        assertThat(files.get(249).filename()).isEqualTo("f249.java");
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test
    void stopsAfterAnEmptyPageWhenTheCountIsAnExactMultiple() {
        totalFiles = 200;
        assertThat(github.fetchChangedFiles("o", "r", 1)).hasSize(200);
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test
    void pageCountIsBoundedByGitHubsListingLimit() {
        totalFiles = 5_000;
        assertThat(github.fetchChangedFiles("o", "r", 1)).hasSize(GitHubService.FILES_PER_PAGE * GitHubService.MAX_FILE_PAGES);
        assertThat(requests.get()).isEqualTo(GitHubService.MAX_FILE_PAGES);
    }
}
