package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the review_sessions table.
 * Stores one row per completed PR analysis.
 */
@Entity
@Table(name = "review_sessions")
public class ReviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_owner", nullable = false)
    private String repositoryOwner;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "pull_request_number")
    private Integer pullRequestNumber;

    @Column(name = "pull_request_url")
    private String pullRequestUrl;

    @Column(name = "diff_hash", nullable = false)
    private String diffHash;

    @Column(name = "cache_key", nullable = false)
    private String cacheKey;

    // "github" or "sample"
    @Column(nullable = false)
    private String mode;

    // Stores the full ReviewResult as a JSON string
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    // --- constructors ---

    public ReviewSession() {}

    public ReviewSession(String repositoryOwner, String repositoryName,
                         Integer pullRequestNumber, String pullRequestUrl,
                         String diffHash, String cacheKey,
                         String mode, String resultJson) {
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
        this.pullRequestNumber = pullRequestNumber;
        this.pullRequestUrl = pullRequestUrl;
        this.diffHash = diffHash;
        this.cacheKey = cacheKey;
        this.mode = mode;
        this.resultJson = resultJson;
    }

    // --- getters ---

    public UUID getId() { return id; }
    public String getRepositoryOwner() { return repositoryOwner; }
    public String getRepositoryName() { return repositoryName; }
    public Integer getPullRequestNumber() { return pullRequestNumber; }
    public String getPullRequestUrl() { return pullRequestUrl; }
    public String getDiffHash() { return diffHash; }
    public String getCacheKey() { return cacheKey; }
    public String getMode() { return mode; }
    public String getResultJson() { return resultJson; }
    public Instant getCreatedAt() { return createdAt; }
}
