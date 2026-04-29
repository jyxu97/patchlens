package com.patchlens.repository;

import com.patchlens.model.RepositoryContextChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ContextChunkRepository extends JpaRepository<RepositoryContextChunk, UUID> {

    /**
     * Finds the top-k most similar chunks to the given query vector,
     * filtered by repository. Uses pgvector's cosine distance operator (<=>) .
     *
     * The query vector is passed as a string "[0.1,0.2,...]" and cast to
     * the vector type inside the query.
     */
    @Query(value = """
            SELECT * FROM repository_context_chunks
            WHERE repository_owner = :owner
              AND repository_name  = :repo
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :k
            """, nativeQuery = true)
    List<RepositoryContextChunk> findTopKSimilar(
            @Param("owner") String owner,
            @Param("repo") String repo,
            @Param("queryVector") String queryVector,
            @Param("k") int k
    );

    /** Deletes all chunks for a repository — used when re-indexing. */
    void deleteByRepositoryOwnerAndRepositoryName(String owner, String repo);
}