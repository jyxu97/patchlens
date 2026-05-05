package com.patchlens.repository;

import com.patchlens.model.RepositoryContextChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ContextChunkRepository extends JpaRepository<RepositoryContextChunk, UUID> {

    /**
     * Finds the top-k most similar chunks to the given query vector, returning
     * file_path, content, and cosine similarity score for each result.
     *
     * Each row in the result is an Object[] with three elements:
     *   [0] file_path        (String)
     *   [1] content          (String)
     *   [2] similarity_score (double) — 1 minus cosine distance, range 0.0–1.0
     */
    @Query(value = """
            SELECT file_path, content,
                   1 - (embedding <=> CAST(:queryVector AS vector)) AS similarity_score
            FROM repository_context_chunks
            WHERE repository_owner = :owner
              AND repository_name  = :repo
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :k
            """, nativeQuery = true)
    List<Object[]> findTopKSimilarWithScore(
            @Param("owner") String owner,
            @Param("repo") String repo,
            @Param("queryVector") String queryVector,
            @Param("k") int k
    );

    /** Deletes all chunks for a repository — used when re-indexing. */
    void deleteByRepositoryOwnerAndRepositoryName(String owner, String repo);
}