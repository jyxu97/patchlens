package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.PullRequestMetadata;
import com.patchlens.model.RetrievedContextChunk;
import com.patchlens.model.RiskScore;
import com.patchlens.repository.ContextChunkRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ContextRetrievalService {

    private static final int TOP_K = 5;

    private final ContextChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final RepoIndexStateService indexState;

    public ContextRetrievalService(ContextChunkRepository chunkRepository,
                                   EmbeddingService embeddingService,
                                   RepoIndexStateService indexState) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.indexState = indexState;
    }

    /**
     * Retrieves the top-k most relevant context chunks for a given PR.
     * Builds a query string from PR metadata and risky files, embeds it,
     * then performs a cosine similarity search in pgvector.
     *
     * @return chunks with file path, full content, and similarity score
     */
    public List<RetrievedContextChunk> retrieve(PullRequestMetadata metadata,
                                                List<ChangedFile> files,
                                                List<RiskScore> riskScores) {
        return retrieve(metadata, files, riskScores, List.of());
    }

    /**
     * Retrieves the top-k most relevant context chunks, augmented with
     * retrieval queries from diff understanding (Phase 2).
     *
     * @param additionalQueries queries extracted from DiffUnderstanding.retrievalQueries()
     * @return chunks with file path, full content, and similarity score
     */
    public List<RetrievedContextChunk> retrieve(PullRequestMetadata metadata,
                                                List<ChangedFile> files,
                                                List<RiskScore> riskScores,
                                                List<String> additionalQueries) {
        String query = buildQuery(metadata, files, riskScores, additionalQueries);
        float[] queryEmbedding = embeddingService.embed(query);
        String queryVector = embeddingService.toVectorString(queryEmbedding);

        // Only the repository's active index version is searched, so a re-index running
        // concurrently can never mix its partial chunks into this analysis. Repositories without
        // a tracked version (seeded fixtures) fall back to all chunks.
        Optional<String> version = indexState.activeVersion(metadata.owner(), metadata.repo());
        List<Object[]> rows = version.isPresent()
                ? chunkRepository.findTopKSimilarWithScoreForVersion(metadata.owner(), metadata.repo(), version.get(), queryVector, TOP_K)
                : chunkRepository.findTopKSimilarWithScore(metadata.owner(), metadata.repo(), queryVector, TOP_K);
        return rows
                .stream()
                .map(row -> new RetrievedContextChunk(
                        (String) row[0],                                                 // file_path
                        (String) row[1],                                                 // content
                        row[2] instanceof Number n ? n.doubleValue() : 0.0               // similarity_score
                ))
                .toList();
    }

    String buildQuery(PullRequestMetadata metadata,
                      List<ChangedFile> files,
                      List<RiskScore> riskScores,
                      List<String> additionalQueries) {
        StringBuilder sb = new StringBuilder();
        sb.append(metadata.title()).append(". ");

        if (!metadata.body().isBlank()) {
            // Use first 200 chars of the PR body to keep the query focused
            String bodySnippet = metadata.body().length() > 200
                    ? metadata.body().substring(0, 200)
                    : metadata.body();
            sb.append(bodySnippet).append(". ");
        }

        // Include the names of the top risky files — they signal what the PR is about
        riskScores.stream()
                .filter(rs -> rs.riskLevel() != RiskScore.RiskLevel.low)
                .limit(3)
                .forEach(rs -> sb.append(rs.filename()).append(" "));

        // Append any additional queries from diff understanding (Phase 2)
        additionalQueries.stream()
                .limit(3)
                .forEach(q -> sb.append(q).append(" "));

        return sb.toString().strip();
    }
}
