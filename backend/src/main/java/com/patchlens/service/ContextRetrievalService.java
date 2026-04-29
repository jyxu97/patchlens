package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.PullRequestMetadata;
import com.patchlens.model.RepositoryContextChunk;
import com.patchlens.model.RiskScore;
import com.patchlens.repository.ContextChunkRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ContextRetrievalService {

    private static final int TOP_K = 5;

    private final ContextChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;

    public ContextRetrievalService(ContextChunkRepository chunkRepository,
                                   EmbeddingService embeddingService) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * Retrieves the top-k most relevant context chunks for a given PR.
     * Builds a query string from PR metadata and risky files, embeds it,
     * then performs a cosine similarity search in pgvector.
     */
    public List<RepositoryContextChunk> retrieve(PullRequestMetadata metadata,
                                                  List<ChangedFile> files,
                                                  List<RiskScore> riskScores) {
        String query = buildQuery(metadata, files, riskScores);
        float[] queryEmbedding = embeddingService.embed(query);
        String queryVector = embeddingService.toVectorString(queryEmbedding);

        return chunkRepository.findTopKSimilar(
                metadata.owner(), metadata.repo(), queryVector, TOP_K
        );
    }

    /**
     * Builds a natural-language query string that captures the intent of the PR.
     * The more specific the query, the more relevant the retrieved chunks will be.
     */
    private String buildQuery(PullRequestMetadata metadata,
                               List<ChangedFile> files,
                               List<RiskScore> riskScores) {
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

        return sb.toString().strip();
    }
}
