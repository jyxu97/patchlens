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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

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
                     int pullRequestNumber, String pullRequestUrl) {
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
        this.pullRequestNumber = pullRequestNumber;
        this.pullRequestUrl = pullRequestUrl;
        this.status = JobStatus.PENDING;
    }

    public UUID getId() { return id; }
    public String getRepositoryOwner() { return repositoryOwner; }
    public String getRepositoryName() { return repositoryName; }
    public int getPullRequestNumber() { return pullRequestNumber; }
    public String getPullRequestUrl() { return pullRequestUrl; }
    public String getDiffHash() { return diffHash; }
    public JobStatus getStatus() { return status; }
    public String getResultJson() { return resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttemptCount() { return attemptCount; }
    public String getTriggerSource() { return triggerSource; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setDiffHash(String diffHash) { this.diffHash = diffHash; }
    public void setStatus(JobStatus status) { this.status = status; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
