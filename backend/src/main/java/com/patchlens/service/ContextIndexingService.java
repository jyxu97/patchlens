package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.RepositoryContextChunk;
import com.patchlens.repository.ContextChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ContextIndexingService {

    private static final Logger log = LoggerFactory.getLogger(ContextIndexingService.class);
    private static final int MAX_CHUNK_CHARS = 800;
    private static final int MIN_CHUNK_CHARS = 50;

    private final RestClient restClient;
    private final EmbeddingService embeddingService;
    private final ContextChunkRepository chunkRepository;

    public ContextIndexingService(
            @Qualifier("githubRestClient") RestClient restClient,
            EmbeddingService embeddingService,
            ContextChunkRepository chunkRepository) {
        this.restClient = restClient;
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
    }

    /**
     * Returns true if the repository has already been indexed.
     * Used to decide whether auto-indexing is needed before analysis.
     */
    public boolean isIndexed(String owner, String repo) {
        return chunkRepository.existsByRepositoryOwnerAndRepositoryName(owner, repo);
    }

    /**
     * Auto-indexes a repository on first analysis: README.md plus up to 5 changed files.
     * Only called when isIndexed() returns false.
     */
    public void autoIndex(String owner, String repo, List<ChangedFile> changedFiles) {
        List<String> filesToIndex = new ArrayList<>();
        filesToIndex.add("README.md");
        changedFiles.stream()
                .map(ChangedFile::filename)
                .limit(5)
                .forEach(filesToIndex::add);
        log.info("Auto-indexing {}/{} for first-time analysis ({} files)", owner, repo, filesToIndex.size());
        indexFiles(owner, repo, filesToIndex);
    }

    /**
     * Indexes a list of files from a GitHub repository.
     * Existing chunks for the repository are deleted first (full re-index).
     * Returns the total number of chunks indexed.
     */
    public int indexFiles(String owner, String repo, List<String> filePaths) {
        // Delete existing chunks so re-indexing always produces a clean state
        chunkRepository.deleteByRepositoryOwnerAndRepositoryName(owner, repo);

        int totalChunks = 0;
        for (String filePath : filePaths) {
            try {
                String content = fetchFileContent(owner, repo, filePath);
                List<String> chunks = chunk(content);
                for (int i = 0; i < chunks.size(); i++) {
                    float[] embedding = embeddingService.embed(chunks.get(i));
                    chunkRepository.save(new RepositoryContextChunk(
                            owner, repo, filePath, i, chunks.get(i), embedding
                    ));
                    totalChunks++;
                }
                log.info("Indexed {} chunks from {}/{}/{}", chunks.size(), owner, repo, filePath);
            } catch (Exception e) {
                // Log and skip — one bad file shouldn't abort the whole indexing job
                log.warn("Failed to index {}/{}/{}: {}", owner, repo, filePath, e.getMessage());
            }
        }
        return totalChunks;
    }

    // --- file fetching ---

    private String fetchFileContent(String owner, String repo, String filePath) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
                .retrieve()
                .body(JsonNode.class);

        // GitHub returns file content as base64-encoded string
        String encoded = response.get("content").asString().replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(encoded));
    }

    // --- chunking ---

    /**
     * Splits text into chunks of roughly MAX_CHUNK_CHARS characters.
     * Splits on paragraph breaks first; long paragraphs are split further.
     */
    List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        // Split on double newlines (paragraph boundaries)
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            if (current.length() + trimmed.length() > MAX_CHUNK_CHARS && current.length() > 0) {
                // Current buffer is full — flush it
                addIfLongEnough(chunks, current.toString());
                current = new StringBuilder();
            }

            if (trimmed.length() > MAX_CHUNK_CHARS) {
                // Single paragraph too long — split by sentence (". ")
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
