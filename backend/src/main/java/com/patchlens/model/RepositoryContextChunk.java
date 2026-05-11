package com.patchlens.model;

import com.patchlens.config.FloatArrayConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for repository_context_chunks table.
 * Each row stores one text chunk and its embedding vector.
 */
@Entity
@Table(name = "repository_context_chunks")
public class RepositoryContextChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_owner", nullable = false)
    private String repositoryOwner;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // Stored as PostgreSQL vector(1536); FloatArrayConverter handles serialization
    @Column(columnDefinition = "vector(1536)")
    @Convert(converter = FloatArrayConverter.class)
    private float[] embedding;

    /** File category (DOC / CONFIG / SOURCE / API_SPEC / CI / BUILD). Null for manually indexed files. */
    @Column(name = "file_type", length = 20)
    private String fileType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public RepositoryContextChunk() {}

    public RepositoryContextChunk(String repositoryOwner, String repositoryName,
                                   String filePath, int chunkIndex,
                                   String content, float[] embedding) {
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
        this.filePath = filePath;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embedding = embedding;
    }

    public UUID getId() { return id; }
    public String getRepositoryOwner() { return repositoryOwner; }
    public String getRepositoryName() { return repositoryName; }
    public String getFilePath() { return filePath; }
    public int getChunkIndex() { return chunkIndex; }
    public String getContent() { return content; }
    public float[] getEmbedding() { return embedding; }
    public String getFileType() { return fileType; }
    public Instant getCreatedAt() { return createdAt; }
}
