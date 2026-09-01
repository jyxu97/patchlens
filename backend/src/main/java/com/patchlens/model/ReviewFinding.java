package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_findings",
       indexes = @Index(name = "idx_review_findings_job_id", columnList = "review_job_id"))
public class ReviewFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "review_job_id", nullable = false)
    private UUID reviewJobId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "line_start")
    private Integer lineStart;

    @Column(name = "line_end")
    private Integer lineEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FindingCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FindingSeverity severity;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String explanation;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false)
    private ValidationStatus validationStatus = ValidationStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public ReviewFinding() {}

    public ReviewFinding(UUID reviewJobId, String filePath, Integer lineStart, Integer lineEnd,
                         FindingCategory category, FindingSeverity severity,
                         String title, String explanation, double confidence, String evidenceJson) {
        this.reviewJobId = reviewJobId;
        this.filePath = filePath;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.category = category;
        this.severity = severity;
        this.title = title;
        this.explanation = explanation;
        this.confidence = confidence;
        this.evidenceJson = evidenceJson;
        this.validationStatus = ValidationStatus.PENDING;
    }

    public UUID getId()                       { return id; }
    public UUID getReviewJobId()              { return reviewJobId; }
    public String getFilePath()               { return filePath; }
    public Integer getLineStart()             { return lineStart; }
    public Integer getLineEnd()               { return lineEnd; }
    public FindingCategory getCategory()      { return category; }
    public FindingSeverity getSeverity()      { return severity; }
    public String getTitle()                  { return title; }
    public String getExplanation()            { return explanation; }
    public double getConfidence()             { return confidence; }
    public String getEvidenceJson()           { return evidenceJson; }
    public ValidationStatus getValidationStatus() { return validationStatus; }
    public Instant getCreatedAt()             { return createdAt; }

    public void setValidationStatus(ValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }
}
