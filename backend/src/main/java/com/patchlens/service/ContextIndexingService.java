package com.patchlens.service;

import com.patchlens.model.FileType;
import com.patchlens.model.RepoIndexMetadata;
import com.patchlens.repository.ContextChunkRepository;
import com.patchlens.repository.RepoIndexMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.*;

@Service
public class ContextIndexingService {

    private static final Logger log = LoggerFactory.getLogger(ContextIndexingService.class);

    // Chunking limits
    private static final int MAX_CHUNK_CHARS = 800;
    private static final int MIN_CHUNK_CHARS = 50;

    // Discovery limits — scanning paths is cheap; fetching + embedding is expensive
    private static final int MAX_INDEXED_FILES = 50;
    private static final int MAX_FILE_CHARS    = 20_000;

    // Matches TTL_GITHUB_PR in CacheService: within one analysis cycle, repo docs are considered stable.
    private static final Duration SHA_CACHE_TTL = Duration.ofHours(24);

    private final RestClient restClient;
    private final EmbeddingService embeddingService;
    private final ContextChunkRepository chunkRepository;
    private final RepoIndexMetadataRepository metadataRepository;
    private final CacheService cacheService;

    public ContextIndexingService(
            @Qualifier("githubRestClient") RestClient restClient,
            EmbeddingService embeddingService,
            ContextChunkRepository chunkRepository,
            RepoIndexMetadataRepository metadataRepository,
            CacheService cacheService) {
        this.restClient = restClient;
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
        this.metadataRepository = metadataRepository;
        this.cacheService = cacheService;
    }

    // -------------------------------------------------------------------------
    // Public: index state queries
    // -------------------------------------------------------------------------

    /**
     * Returns true if any chunks exist for the given repository.
     * Used by SampleContextSeeder to skip already-seeded sample repos.
     */
    public boolean isIndexed(String owner, String repo) {
        return chunkRepository.existsByRepositoryOwnerAndRepositoryName(owner, repo);
    }

    /**
     * Returns true if the repository has been indexed AND the index matches the
     * current default-branch tree SHA (i.e. the index is not stale).
     *
     * Used by ReviewController before each PR analysis to decide whether to
     * trigger a background re-index.
     */
    public boolean isUpToDate(String owner, String repo) {
        Optional<RepoIndexMetadata> meta =
                metadataRepository.findByRepoFullName(owner + "/" + repo);
        if (meta.isEmpty()) return false;

        String currentSha = fetchCurrentTreeSha(owner, repo, meta.get().getBaseBranch());
        if (currentSha == null) return true; // can't check — assume up to date

        return currentSha.equals(meta.get().getIndexedTreeSha());
    }

    // -------------------------------------------------------------------------
    // Public: indexing entry points
    // -------------------------------------------------------------------------

    /**
     * Discovers and indexes the highest-signal files from a GitHub repository.
     *
     * Runs asynchronously so PR analysis is never blocked by indexing.
     * Flow:
     *   1. Fetch full file tree from GitHub Trees API
     *   2. Score every file by path / name / extension
     *   3. Select top MAX_INDEXED_FILES by score
     *   4. Fetch content, chunk, embed, store in pgvector
     *   5. Write repo_index_metadata (sha, branch, file count)
     *   6. Cache the tree SHA in Redis so staleness checks are fast
     *
     * Only called when isUpToDate() returns false.
     */
    @Async
    public void autoIndex(String owner, String repo) {
        log.info("Auto-indexing {}/{}: discovering high-signal files", owner, repo);
        try {
            String defaultBranch = fetchDefaultBranch(owner, repo);
            RepoTree tree = fetchRepoTree(owner, repo, defaultBranch);

            if (tree.truncated()) {
                log.warn("Tree response for {}/{} is truncated; indexing available files only", owner, repo);
            }

            List<ScoredFile> selected = scoreAndSelect(tree.files());
            log.info("Selected {}/{} files to index for {}/{}",
                    selected.size(), tree.files().size(), owner, repo);

            // Delete stale chunks then insert fresh ones
            chunkRepository.deleteByRepositoryOwnerAndRepositoryName(owner, repo);
            int totalChunks = indexScoredFiles(owner, repo, selected);

            // Persist metadata and update SHA cache
            metadataRepository.deleteByRepoFullName(owner + "/" + repo);
            metadataRepository.save(new RepoIndexMetadata(
                    owner + "/" + repo, defaultBranch, tree.sha(),
                    selected.size(), tree.truncated()
            ));
            cacheService.putIndexSha(cacheService.indexShaKey(owner, repo), tree.sha(), SHA_CACHE_TTL);

            log.info("Auto-indexing complete for {}/{}: {} files, {} chunks",
                    owner, repo, selected.size(), totalChunks);
        } catch (Exception e) {
            log.error("Auto-indexing failed for {}/{}: {}", owner, repo, e.getMessage(), e);
        }
    }

    /**
     * Indexes a hand-picked list of files from a GitHub repository.
     * Used by the admin endpoint POST /api/repositories/index.
     * Existing chunks are deleted first (full re-index).
     * Returns the total number of chunks indexed.
     */
    public int indexFiles(String owner, String repo, List<String> filePaths) {
        chunkRepository.deleteByRepositoryOwnerAndRepositoryName(owner, repo);
        int totalChunks = 0;
        for (String filePath : filePaths) {
            try {
                String content = fetchFileContent(owner, repo, filePath);
                List<String> chunks = chunk(content);
                for (int i = 0; i < chunks.size(); i++) {
                    float[] embedding = embeddingService.embed(chunks.get(i));
                    chunkRepository.insertChunk(owner, repo, filePath, i, chunks.get(i),
                            embeddingService.toVectorString(embedding), null);
                    totalChunks++;
                }
                log.info("Indexed {} chunks from {}/{}/{}", chunks.size(), owner, repo, filePath);
            } catch (Exception e) {
                log.warn("Failed to index {}/{}/{}: {}", owner, repo, filePath, e.getMessage());
            }
        }
        return totalChunks;
    }

    /**
     * Indexes pre-written text content without fetching from GitHub.
     * Used to seed fixture context chunks for sample PRs at startup.
     * Only called when isIndexed() returns false, so no delete step is needed.
     */
    public void indexTextContent(String owner, String repo, String filePath, String content) {
        List<String> chunks = chunk(content);
        for (int i = 0; i < chunks.size(); i++) {
            float[] embedding = embeddingService.embed(chunks.get(i));
            chunkRepository.insertChunk(owner, repo, filePath, i, chunks.get(i),
                    embeddingService.toVectorString(embedding), null);
        }
        log.info("Seeded {} chunks for {}/{}/{}", chunks.size(), owner, repo, filePath);
    }

    // -------------------------------------------------------------------------
    // Private: GitHub API calls
    // -------------------------------------------------------------------------

    private String fetchDefaultBranch(String owner, String repo) {
        JsonNode repoInfo = restClient.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .body(JsonNode.class);
        return repoInfo.get("default_branch").asString();
    }

    private RepoTree fetchRepoTree(String owner, String repo, String branch) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                .retrieve()
                .body(JsonNode.class);

        String sha = response.get("sha").asString();
        boolean truncated = response.path("truncated").asBoolean(false);

        List<TreeEntry> entries = new ArrayList<>();
        for (JsonNode node : response.get("tree")) {
            if ("blob".equals(node.get("type").asString())) {
                long size = node.path("size").asLong(0);
                entries.add(new TreeEntry(node.get("path").asString(), size));
            }
        }
        return new RepoTree(sha, entries, truncated);
    }

    /**
     * Fetches the current root tree SHA for staleness detection.
     * Non-recursive — only the SHA matters here, not the file list.
     * Result is cached in Redis for SHA_CACHE_TTL to avoid per-request API calls.
     * Returns null if the SHA cannot be fetched (network/auth error).
     */
    private String fetchCurrentTreeSha(String owner, String repo, String branch) {
        String cacheKey = cacheService.indexShaKey(owner, repo);
        Optional<String> cached = cacheService.getIndexSha(cacheKey);
        if (cached.isPresent()) return cached.get();

        try {
            JsonNode response = restClient.get()
                    .uri("/repos/{owner}/{repo}/git/trees/{branch}", owner, repo, branch)
                    .retrieve()
                    .body(JsonNode.class);
            String sha = response.get("sha").asString();
            cacheService.putIndexSha(cacheKey, sha, SHA_CACHE_TTL);
            return sha;
        } catch (Exception e) {
            log.warn("Failed to fetch current tree SHA for {}/{}: {}", owner, repo, e.getMessage());
            return null;
        }
    }

    private String fetchFileContent(String owner, String repo, String filePath) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
                .retrieve()
                .body(JsonNode.class);
        String encoded = response.get("content").asString().replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(encoded));
    }

    // -------------------------------------------------------------------------
    // Private: file discovery and scoring
    // -------------------------------------------------------------------------

    private int indexScoredFiles(String owner, String repo, List<ScoredFile> files) {
        int total = 0;
        for (ScoredFile file : files) {
            try {
                String content = fetchFileContent(owner, repo, file.path());
                if (content.length() > MAX_FILE_CHARS) {
                    content = content.substring(0, MAX_FILE_CHARS);
                }
                List<String> chunks = chunk(content);
                for (int i = 0; i < chunks.size(); i++) {
                    float[] embedding = embeddingService.embed(chunks.get(i));
                    chunkRepository.insertChunk(owner, repo, file.path(), i, chunks.get(i),
                            embeddingService.toVectorString(embedding), file.fileType().name());
                    total++;
                }
                log.debug("Indexed {} chunks from {}/{}/{} ({})",
                        chunks.size(), owner, repo, file.path(), file.fileType());
            } catch (Exception e) {
                log.warn("Failed to index {}/{}/{}: {}", owner, repo, file.path(), e.getMessage());
            }
        }
        return total;
    }

    private List<ScoredFile> scoreAndSelect(List<TreeEntry> entries) {
        return entries.stream()
                .filter(e -> !shouldExclude(e.path()))
                .map(e -> new ScoredFile(e.path(), scoreFile(e.path()), classifyFile(e.path())))
                .filter(f -> f.score() > 0)
                .sorted(Comparator.comparingInt(ScoredFile::score).reversed())
                .limit(MAX_INDEXED_FILES)
                .toList();
    }

    private boolean shouldExclude(String path) {
        String lower = path.toLowerCase();
        if (lower.startsWith("node_modules/") || lower.contains("/node_modules/")) return true;
        if (lower.startsWith("target/")       || lower.startsWith(".git/"))        return true;
        if (lower.startsWith("dist/")         || lower.startsWith("build/"))       return true;
        if (lower.startsWith("vendor/")       || lower.contains("/vendor/"))       return true;
        if (lower.contains("__pycache__"))                                          return true;
        if (lower.endsWith(".min.js")  || lower.endsWith(".min.css"))              return true;
        if (lower.endsWith("-lock.json") || lower.endsWith(".lock") || lower.endsWith(".sum")) return true;
        String ext = getExtension(getFilename(lower));
        return Set.of("png","jpg","jpeg","gif","ico","svg","pdf",
                "woff","woff2","ttf","eot","zip","tar","gz",
                "jar","war","class","pyc","exe","dll").contains(ext);
    }

    /** Returns a score reflecting how useful this file is as RAG context. Higher = better. */
    /* package-private for tests */ int scoreFile(String path) {
        String lower    = path.toLowerCase();
        String filename = getFilename(lower);
        String ext      = getExtension(filename);
        int score = 0;

        // --- filename bonuses ---
        if (filename.startsWith("readme"))                                  score += 5;
        if (filename.startsWith("contributing"))                            score += 4;
        if (filename.startsWith("architecture") || filename.startsWith("changelog")) score += 4;
        if (filename.startsWith("security") || filename.startsWith("code_of_conduct")) score += 3;

        // --- documentation directories ---
        if (lower.startsWith("docs/")         || lower.contains("/docs/"))         score += 4;
        if (lower.startsWith("doc/")          || lower.contains("/doc/"))          score += 4;
        if (lower.contains("/architecture/")  || lower.startsWith("architecture/")) score += 4;
        if (lower.contains("/design/")        || lower.startsWith("design/"))       score += 4;
        if (lower.contains("/adr/")           || lower.startsWith("adr/"))          score += 4;
        if (lower.contains("/rfcs/")          || lower.startsWith("rfcs/")
                || lower.contains("/rfc/")    || lower.startsWith("rfc/"))          score += 3;
        if (lower.contains("/wiki/")          || lower.startsWith("wiki/"))         score += 3;
        if (lower.contains("/spec/")          || lower.startsWith("spec/"))         score += 3;

        // --- config / build files ---
        if (Set.of("pom.xml","build.gradle","build.gradle.kts","package.json",
                   "requirements.txt","pyproject.toml","go.mod","cargo.toml",
                   "build.sbt","mix.exs").contains(filename))                       score += 3;
        if (filename.equals("dockerfile") || filename.startsWith("docker-compose")) score += 3;
        if (filename.equals("application.yml") || filename.equals("application.yaml")
                || filename.equals("application.properties"))                       score += 3;
        if (lower.startsWith(".github/") && lower.contains("workflows"))            score += 3;

        // --- API spec ---
        if (filename.contains("openapi") || filename.contains("swagger"))           score += 4;
        if (ext.equals("proto"))                                                     score += 3;
        if (lower.contains("/api/") || lower.startsWith("api/"))                    score += 2;

        // --- source code in main paths (lower signal than docs, useful as fallback) ---
        if (lower.contains("/src/main/") || lower.contains("/main/java/")
                || lower.contains("/main/kotlin/"))                                  score += 2;
        if (lower.contains("/service/")    || lower.contains("/services/"))          score += 1;
        if (lower.contains("/controller/") || lower.contains("/controllers/"))       score += 1;
        if (lower.contains("/repository/") || lower.contains("/repositories/"))     score += 1;
        if (lower.contains("/model/")      || lower.contains("/entity/"))            score += 1;
        if (lower.contains("/config/")     || lower.contains("/configuration/"))     score += 1;

        // --- extension bonuses ---
        if (Set.of("md","mdx","rst","txt","adoc","asciidoc").contains(ext))         score += 1;
        if (Set.of("yml","yaml","json","xml","properties","toml").contains(ext))    score += 1;
        if (Set.of("java","kt","py","ts","tsx","js","go","rs","cs",
                   "rb","php","swift").contains(ext))                               score += 1;

        // --- test file penalty ---
        if (lower.contains("/test/") || lower.contains("/tests/")
                || lower.contains("__tests__") || filename.contains("test")
                || filename.endsWith("spec.ts") || filename.endsWith("spec.js"))    score -= 2;

        return score;
    }

    private FileType classifyFile(String path) {
        String lower    = path.toLowerCase();
        String filename = getFilename(lower);
        String ext      = getExtension(filename);

        if (ext.equals("proto") || filename.contains("openapi") || filename.contains("swagger")
                || (lower.contains("/api/") && Set.of("yml","yaml","json").contains(ext)))
            return FileType.API_SPEC;

        if (lower.startsWith(".github/workflows/") || filename.equals("jenkinsfile")
                || filename.equals(".travis.yml")  || filename.equals("circle.yml"))
            return FileType.CI;

        if (Set.of("pom.xml","build.gradle","build.gradle.kts","package.json",
                   "requirements.txt","pyproject.toml","go.mod","cargo.toml").contains(filename))
            return FileType.BUILD;

        if (filename.equals("dockerfile") || filename.startsWith("docker-compose")
                || filename.contains("application.")
                || lower.contains("/config/") || lower.contains("/configuration/"))
            return FileType.CONFIG;

        if (Set.of("java","kt","py","ts","tsx","js","go","rs","cs","rb","php","swift").contains(ext))
            return FileType.SOURCE;

        return FileType.DOC;
    }

    private String getFilename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }

    // -------------------------------------------------------------------------
    // Private: internal DTOs
    // -------------------------------------------------------------------------

    private record RepoTree(String sha, List<TreeEntry> files, boolean truncated) {}
    private record TreeEntry(String path, long size) {}
    private record ScoredFile(String path, int score, FileType fileType) {}

    // -------------------------------------------------------------------------
    // Private: chunking (unchanged)
    // -------------------------------------------------------------------------

    /**
     * Splits text into chunks of roughly MAX_CHUNK_CHARS characters.
     * Splits on paragraph breaks first; long paragraphs are split further by sentence.
     */
    List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            if (current.length() + trimmed.length() > MAX_CHUNK_CHARS && current.length() > 0) {
                addIfLongEnough(chunks, current.toString());
                current = new StringBuilder();
            }

            if (trimmed.length() > MAX_CHUNK_CHARS) {
                for (String sentence : trimmed.split("(?<=\\. )")) {
                    if (current.length() + sentence.length() > MAX_CHUNK_CHARS && current.length() > 0) {
                        addIfLongEnough(chunks, current.toString());
                        current = new StringBuilder();
                    }
                    current.append(sentence).append(" ");
                }
            } else {
                current.append(trimmed).append("\n\n");
            }
        }

        addIfLongEnough(chunks, current.toString());
        return chunks;
    }

    private void addIfLongEnough(List<String> chunks, String text) {
        String trimmed = text.strip();
        if (trimmed.length() >= MIN_CHUNK_CHARS) {
            chunks.add(trimmed);
        }
    }
}
