package com.patchlens.service;

import com.patchlens.repository.ContextChunkRepository;
import com.patchlens.repository.RepoIndexMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ContextIndexingService.scoreFile().
 *
 * scoreFile() is the core logic of the file-discovery pipeline: it determines
 * which repository files are worth indexing as RAG context. These tests verify
 * the relative ordering and penalty rules without any GitHub or database I/O.
 */
@ExtendWith(MockitoExtension.class)
class ContextIndexingServiceScoringTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private ContextChunkRepository chunkRepository;
    @Mock private RepoIndexMetadataRepository metadataRepository;
    @Mock private CacheService cacheService;
    @Mock private RestClient restClient;

    private ContextIndexingService service;

    @BeforeEach
    void setUp() {
        service = new ContextIndexingService(restClient, embeddingService,
                chunkRepository, metadataRepository, cacheService);
    }

    // --- documentation files score highest ---

    @Test
    void readmeShouldScoreHighest() {
        assertThat(service.scoreFile("README.md")).isGreaterThanOrEqualTo(5);
    }

    @Test
    void contributingFileShouldScoreHigh() {
        assertThat(service.scoreFile("CONTRIBUTING.md")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void architectureDocShouldScoreHigh() {
        assertThat(service.scoreFile("docs/architecture.md")).isGreaterThanOrEqualTo(5);
    }

    @Test
    void adrFileShouldScoreHigh() {
        assertThat(service.scoreFile("adr/0001-use-postgresql.md")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void rfcFileShouldScoreHigh() {
        assertThat(service.scoreFile("rfcs/caching-strategy.md")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void nestedDocsFileShouldScoreHigh() {
        assertThat(service.scoreFile("docs/api/authentication.md")).isGreaterThanOrEqualTo(5);
    }

    // --- API spec files score high ---

    @Test
    void openapiFileShouldScoreHigh() {
        assertThat(service.scoreFile("openapi.yaml")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void swaggerFileShouldScoreHigh() {
        assertThat(service.scoreFile("api/swagger.yml")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void protoFileShouldScorePositive() {
        assertThat(service.scoreFile("proto/user.proto")).isGreaterThan(0);
    }

    // --- config and build files score positively ---

    @Test
    void pomXmlShouldScorePositive() {
        assertThat(service.scoreFile("pom.xml")).isGreaterThan(0);
    }

    @Test
    void dockerfileShouldScorePositive() {
        assertThat(service.scoreFile("Dockerfile")).isGreaterThan(0);
    }

    @Test
    void applicationYmlShouldScorePositive() {
        assertThat(service.scoreFile("src/main/resources/application.yml")).isGreaterThan(0);
    }

    @Test
    void ciWorkflowShouldScorePositive() {
        assertThat(service.scoreFile(".github/workflows/ci.yml")).isGreaterThan(0);
    }

    // --- source files score lower than docs ---

    @Test
    void sourceFileShouldScoreLowerThanReadme() {
        int readmeScore = service.scoreFile("README.md");
        int sourceScore = service.scoreFile("src/main/java/com/example/UserService.java");
        assertThat(readmeScore).isGreaterThan(sourceScore);
    }

    @Test
    void docsFileShouldScoreHigherThanSourceFile() {
        int docsScore  = service.scoreFile("docs/architecture.adoc");
        int sourceScore = service.scoreFile("src/main/java/com/example/service/OrderService.java");
        assertThat(docsScore).isGreaterThan(sourceScore);
    }

    // --- test files are penalised ---

    @Test
    void testFileShouldScoreLowerThanEquivalentSourceFile() {
        int sourceScore = service.scoreFile("src/main/java/com/example/UserService.java");
        int testScore   = service.scoreFile("src/test/java/com/example/UserServiceTest.java");
        assertThat(sourceScore).isGreaterThan(testScore);
    }

    @Test
    void specFileShouldBepenalised() {
        int sourceScore = service.scoreFile("src/main/java/com/example/UserService.java");
        int specScore   = service.scoreFile("src/test/java/com/example/UserServiceSpec.java");
        assertThat(sourceScore).isGreaterThan(specScore);
    }

    // --- case insensitivity ---

    @Test
    void readmeShouldScoreHighRegardlessOfCase() {
        int lower = service.scoreFile("readme.md");
        int upper = service.scoreFile("README.MD");
        assertThat(lower).isEqualTo(upper);
        assertThat(lower).isGreaterThanOrEqualTo(5);
    }

    @Test
    void adocExtensionShouldScorePositive() {
        // Spring Boot uses .adoc, not .md — must not be penalised
        assertThat(service.scoreFile("docs/reference/html/index.adoc")).isGreaterThan(0);
    }
}
