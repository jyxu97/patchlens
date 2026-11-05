package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "review_jobs",
    indexes = {
        @Index(name = "idx_review_jobs_owner_repo_pr",
               columnList = "repository_owner, repository_name, pull_request_number")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_review_jobs_pr_head_sha",
            columnNames = {"repository_owner", "repository_name", "pull_request_number", "head_sha"}
        )
    }
)
public class ReviewJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_owner", nullable = false)
    private String repositoryOwner;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "pull_request_number", nullable = false)
    private int pullRequestNumber;

    @Column(name = "pull_request_url", nullable = false)
    private String pullRequestUrl;

    /**
     * GitHub commit SHA from pull_request.head.sha in the webhook payload.
     * Used as the idempotency key — same PR + same commit = same review job.
     * Part of the unique constraint uq_review_jobs_pr_head_sha.
     */
    @Column(name = "head_sha", length = 40)
    private String headSha;

    @Column(name = "diff_hash")
    private String diffHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "trigger_source", nullable = false)
    private String triggerSource = "webhook";

    @Column(name = "finding_count", nullable = false)
    private int findingCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Which files were reviewed / skipped / truncated, and the index freshness used (JSON). */
    @Column(name = "coverage_json", columnDefinition = "TEXT")
    private String coverageJson;

    // ---- worker lease (atomic ownership; nullable for in-place schema upgrade) ----

    /** Opaque token of the delivery currently processing the job; null when unowned. */
    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    // ---- transactional outbox ----

    /** Last time a ReviewJobMessage for this job was handed to RabbitMQ; null = never published. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts")
    private Integer publishAttempts;

    // ---- GitHub comment delivery (independent of analysis status) ----

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_status")
    private CommentStatus commentStatus;

    /** Rendered comment (with the hidden job marker), so delivery can be retried without re-analysis. */
    @Column(name = "comment_body", columnDefinition = "TEXT")
    private String commentBody;

    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "comment_attempts")
    private Integer commentAttempts;

    @Column(name = "comment_last_error", columnDefinition = "TEXT")
    private String commentLastError;

    @Column(name = "comment_next_attempt_at")
    private Instant commentNextAttemptAt;

    @Column(name = "comment_lease_until")
    private Instant commentLeaseUntil;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public ReviewJob() {}

    public ReviewJob(String repositoryOwner, String repositoryName,
                     int pullRequestNumber, String pullRequestUrl, String headSha) {
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
        this.pullRequestNumber = pullRequestNumber;
        this.pullRequestUrl = pullRequestUrl;
        this.headSha = headSha;
        this.status = JobStatus.PENDING;
    }

    public UUID getId() { return id; }
    public String getRepositoryOwner() { return repositoryOwner; }
    public String getRepositoryName() { return repositoryName; }
    public int getPullRequestNumber() { return pullRequestNumber; }
    public String getPullRequestUrl() { return pullRequestUrl; }
    public String getHeadSha() { return headSha; }
    public String getDiffHash() { return diffHash; }
    public JobStatus getStatus() { return status; }
    public String getResultJson() { return resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttemptCount() { return attemptCount; }
    public String getTriggerSource() { return triggerSource; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getFindingCount() { return findingCount; }

    public void setDiffHash(String diffHash) { this.diffHash = diffHash; }
    public void setStatus(JobStatus status) { this.status = status; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public void setFindingCount(int findingCount) { this.findingCount = findingCount; }

    public String getCoverageJson()            { return coverageJson; }
    public void setCoverageJson(String json)   { this.coverageJson = json; }
    public String getLeaseOwner()              { return leaseOwner; }
    public Instant getLeaseExpiresAt()         { return leaseExpiresAt; }
    public Instant getPublishedAt()            { return publishedAt; }
    public int getPublishAttempts()            { return publishAttempts == null ? 0 : publishAttempts; }
    public CommentStatus getCommentStatus()    { return commentStatus; }
    public String getCommentBody()             { return commentBody; }
    public Long getCommentId()                 { return commentId; }
    public int getCommentAttempts()            { return commentAttempts == null ? 0 : commentAttempts; }
    public String getCommentLastError()        { return commentLastError; }
    public Instant getCommentNextAttemptAt()   { return commentNextAttemptAt; }
    public Instant getCommentLeaseUntil()      { return commentLeaseUntil; }

    public void setLease(String owner, Instant expiresAt) { this.leaseOwner = owner; this.leaseExpiresAt = expiresAt; }
    public void clearLease()                               { this.leaseOwner = null; this.leaseExpiresAt = null; }
    public void setPublishedAt(Instant t)                  { this.publishedAt = t; }
    public void setPublishAttempts(int n)                  { this.publishAttempts = n; }
    public void setCommentStatus(CommentStatus s)          { this.commentStatus = s; }
    public void setCommentBody(String b)                   { this.commentBody = b; }
    public void setCommentId(Long id)                      { this.commentId = id; }
    public void setCommentAttempts(int n)                  { this.commentAttempts = n; }
    public void setCommentLastError(String e)              { this.commentLastError = e; }
    public void setCommentNextAttemptAt(Instant t)         { this.commentNextAttemptAt = t; }
    public void setCommentLeaseUntil(Instant t)            { this.commentLeaseUntil = t; }
}
