package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the analysis_runs table.
 * Stores one row per analysis attempt (cache hit or miss), with latency
 * breakdowns and token usage to support observability and performance tuning.
 */
@Entity
@Table(name = "analysis_runs")
public class AnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** GitHub PR URL; null for sample PR analyses. */
    @Column(name = "pr_url")
    private String prUrl;

    /** Sample fixture ID; null for GitHub PR analyses. */
    @Column(name = "sample_id")
    private String sampleId;

    @Column(name = "diff_hash", nullable = false)
    private String diffHash;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    /** Time spent fetching PR metadata and changed files from GitHub API. 0 for sample PRs. */
    @Column(name = "github_latency_ms", nullable = false)
    private long githubLatencyMs;

    /** Time spent in pgvector top-k retrieval. 0 on cache hits. */
    @Column(name = "retrieval_latency_ms", nullable = false)
    private long retrievalLatencyMs;

    /** Time spent waiting for the OpenAI API response. 0 on cache hits and mock mode. */
    @Column(name = "llm_latency_ms", nullable = false)
    private long llmLatencyMs;

    /** Wall-clock time from request start to response ready. */
    @Column(name = "total_latency_ms", nullable = false)
    private long totalLatencyMs;

    /** Tokens in the prompt sent to the model. 0 on cache hits and mock mode. */
    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    /** Tokens in the model's completion. 0 on cache hits and mock mode. */
    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    /** Model name used (e.g. "gpt-4o-mini"). "mock" in mock mode, "cached" on cache hits. */
    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** "success" or "error". */
    @Column(nullable = false)
    private String status;

    /** Exception message on error; null on success. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Number of risky-file paths in the AI response that were not present in
     * the actual PR diff.  Null for cache hits (no AI call was made).
     */
    @Column(name = "hallucinated_ref_count")
    private Integer hallucinatedRefCount;

    /**
     * Fraction of AI-mentioned risky-file paths that were grounded in the diff
     * (groundedCount / totalRiskyFiles).  Null for cache hits.
     */
    @Column(name = "grounding_rate")
    private Double groundingRate;

    /**
     * FK to the prompt_versions row that was active when this analysis ran.
     * Null for cache hits (no AI call was made).
     */
    @Column(name = "prompt_version_id")
    private UUID promptVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { this.createdAt = Instant.now(); }

    public AnalysisRun() {}

    public AnalysisRun(String prUrl, String sampleId, String diffHash,
                       boolean cacheHit,
                       long githubLatencyMs, long retrievalLatencyMs, long llmLatencyMs, long totalLatencyMs,
                       int promptTokens, int completionTokens,
                       String modelName, String status, String errorMessage,
                       Integer hallucinatedRefCount, Double groundingRate,
                       UUID promptVersionId) {
        this.prUrl = prUrl;
        this.sampleId = sampleId;
        this.diffHash = diffHash;
        this.cacheHit = cacheHit;
        this.githubLatencyMs = githubLatencyMs;
        this.retrievalLatencyMs = retrievalLatencyMs;
        this.llmLatencyMs = llmLatencyMs;
        this.totalLatencyMs = totalLatencyMs;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.modelName = modelName;
        this.status = status;
        this.errorMessage = errorMessage;
        this.hallucinatedRefCount = hallucinatedRefCount;
        this.groundingRate = groundingRate;
        this.promptVersionId = promptVersionId;
    }

    public UUID getId()                      { return id; }
    public String getPrUrl()                 { return prUrl; }
    public String getSampleId()              { return sampleId; }
    public String getDiffHash()              { return diffHash; }
    public boolean isCacheHit()              { return cacheHit; }
    public long getGithubLatencyMs()         { return githubLatencyMs; }
    public long getRetrievalLatencyMs()      { return retrievalLatencyMs; }
    public long getLlmLatencyMs()            { return llmLatencyMs; }
    public long getTotalLatencyMs()          { return totalLatencyMs; }
    public int getPromptTokens()             { return promptTokens; }
    public int getCompletionTokens()         { return completionTokens; }
    public String getModelName()             { return modelName; }
    public String getStatus()                { return status; }
    public String getErrorMessage()          { return errorMessage; }
    public Integer getHallucinatedRefCount() { return hallucinatedRefCount; }
    public Double getGroundingRate()         { return groundingRate; }
    public UUID getPromptVersionId()         { return promptVersionId; }
    public Instant getCreatedAt()            { return createdAt; }
}
