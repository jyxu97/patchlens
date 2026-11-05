package com.patchlens.service;

import com.patchlens.model.IndexFreshness;
import com.patchlens.model.RepoIndexMetadata;
import com.patchlens.repository.ContextChunkRepository;
import com.patchlens.repository.RepoIndexMetadataRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Versioned re-indexing against a fake GitHub API: atomic activation, failure isolation,
 * concurrent re-index requests, pinned file revisions and explicit freshness.
 */
class ContextIndexingVersioningTest {

    private static final String COMMIT = "c0ffee0000000000000000000000000000000001";
    private static final String TREE   = "7ree000000000000000000000000000000000001";

    private HttpServer server;
    private final List<String> contentRefs = new CopyOnWriteArrayList<>();
    private volatile boolean failTree;
    private volatile String currentTree = TREE;
    private volatile CountDownLatch blockContent;

    private ContextChunkRepository chunks;
    private RepoIndexMetadataRepository metaRepo;
    private RepoIndexStateService state;
    private CacheService cache;
    private ContextIndexingService indexing;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        RestClient rc = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();

        chunks = mock(ContextChunkRepository.class);
        metaRepo = mock(RepoIndexMetadataRepository.class);
        when(metaRepo.findByRepoFullName(any())).thenReturn(Optional.empty());
        state = spy(new RepoIndexStateService(metaRepo));
        cache = mock(CacheService.class);
        when(cache.getIndexSha(any())).thenReturn(Optional.empty());
        indexing = new ContextIndexingService(rc, new EmbeddingService(Optional.empty(), "mock"), chunks, metaRepo, cache, state);
    }

    @AfterEach
    void stop() { server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = Objects.toString(ex.getRequestURI().getQuery(), "");
        String body;
        int status = 200;
        if (path.equals("/repos/o/r")) body = "{\"default_branch\":\"main\"}";
        else if (path.equals("/repos/o/r/commits/main")) body = "{\"sha\":\"" + COMMIT + "\",\"commit\":{\"tree\":{\"sha\":\"" + TREE + "\"}}}";
        else if (path.equals("/repos/o/r/git/trees/" + TREE) && query.contains("recursive")) {
            if (failTree) { status = 502; body = "{}"; }
            else body = "{\"sha\":\"" + TREE + "\",\"truncated\":false,\"tree\":["
                    + "{\"path\":\"README.md\",\"type\":\"blob\",\"size\":100},"
                    + "{\"path\":\"docs/architecture.md\",\"type\":\"blob\",\"size\":100}]}";
        }
        else if (path.equals("/repos/o/r/git/trees/main")) body = "{\"sha\":\"" + currentTree + "\"}";
        else if (path.startsWith("/repos/o/r/contents/")) {
            contentRefs.add(query);
            if (blockContent != null) { try { blockContent.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} }
            String text = "Architecture notes. ".repeat(20);
            body = "{\"content\":\"" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\"}";
        }
        else { status = 404; body = "{}"; }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void newVersionIsBuiltBesideTheOldOneThenActivatedAtomicallyThenOldChunksRemoved() {
        Optional<String> version = indexing.reindex("o", "r");

        assertThat(version).contains(TREE);
        InOrder order = inOrder(chunks, state);
        order.verify(chunks).deleteVersion("o", "r", TREE);                   // clear earlier partial build
        order.verify(chunks, atLeastOnce()).insertChunk(eq("o"), eq("r"), any(), anyInt(), any(), any(), any(), eq(TREE));
        order.verify(state).activate("o", "r", "main", TREE, COMMIT, 2, false); // single-row switch
        order.verify(chunks).deleteAllExceptVersion("o", "r", TREE);          // GC only after the switch
        // every file was read at the pinned commit, not at the moving branch
        assertThat(contentRefs).isNotEmpty().allSatisfy(q -> assertThat(q).isEqualTo("ref=" + COMMIT));
    }

    @Test
    void failedReindexLeavesThePreviousVersionActiveAndIntact() {
        failTree = true;
        assertThat(indexing.reindex("o", "r")).isEmpty();
        verify(state, never()).activate(any(), any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(chunks, never()).deleteAllExceptVersion(any(), any(), any());
    }

    @Test
    void concurrentReindexOfTheSameRepositoryRunsOnce() throws Exception {
        blockContent = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Optional<String>> first = pool.submit(() -> indexing.reindex("o", "r"));
        while (contentRefs.isEmpty()) Thread.sleep(10); // first run is mid-index
        Future<Optional<String>> second = pool.submit(() -> indexing.reindex("o", "r"));

        assertThat(second.get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(indexing.isIndexing("o", "r")).isTrue();
        blockContent.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).contains(TREE);
        pool.shutdown();
        verify(state, times(1)).activate(any(), any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void freshnessIsExplicit() {
        assertThat(indexing.freshness("o", "r").status()).isEqualTo(IndexFreshness.Status.NOT_INDEXED);

        when(chunks.existsByRepositoryOwnerAndRepositoryName("o", "r")).thenReturn(true);
        assertThat(indexing.freshness("o", "r").status()).as("untracked legacy chunks").isEqualTo(IndexFreshness.Status.UNKNOWN);

        RepoIndexMetadata meta = new RepoIndexMetadata("o/r", "main", TREE, 2, false);
        when(metaRepo.findByRepoFullName("o/r")).thenReturn(Optional.of(meta));
        assertThat(indexing.freshness("o", "r").status()).isEqualTo(IndexFreshness.Status.FRESH);

        currentTree = "7ree000000000000000000000000000000000002"; // default branch moved on
        IndexFreshness stale = indexing.freshness("o", "r");
        assertThat(stale.status()).isEqualTo(IndexFreshness.Status.STALE);
        assertThat(stale.indexedRevision()).isEqualTo(TREE);

        server.stop(0); // GitHub unreachable: unknown, not "assume fresh"
        assertThat(indexing.freshness("o", "r").status()).isEqualTo(IndexFreshness.Status.UNKNOWN);
    }

    @Test
    void retrievalReadsOnlyTheActiveVersion() {
        ContextRetrievalService retrieval = new ContextRetrievalService(chunks, new EmbeddingService(Optional.empty(), "mock"), state);
        var md = new com.patchlens.model.PullRequestMetadata("o", "r", 1, "t", "", "u");

        retrieval.retrieve(md, List.of(), List.of());
        verify(chunks).findTopKSimilarWithScore(eq("o"), eq("r"), any(), anyInt());

        when(metaRepo.findByRepoFullName("o/r")).thenReturn(Optional.of(new RepoIndexMetadata("o/r", "main", TREE, 2, false)));
        retrieval.retrieve(md, List.of(), List.of());
        verify(chunks).findTopKSimilarWithScoreForVersion(eq("o"), eq("r"), eq(TREE), any(), anyInt());
    }
}
