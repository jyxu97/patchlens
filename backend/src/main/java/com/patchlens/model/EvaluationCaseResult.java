package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-case result row for an offline evaluation run.
 */
@Entity
@Table(name = "evaluation_case_results",
       indexes = @Index(name = "idx_eval_case_run_id", columnList = "run_id"))
public class EvaluationCaseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "case_id", nullable = false)
    private String caseId;

    /** JSON array of DetectedFinding titles/categories for this case. */
    @Column(name = "detected_findings", columnDefinition = "TEXT")
    private String detectedFindings;

    @Column(name = "true_positives", nullable = false)
    private int truePositives;

    @Column(name = "false_positives", nullable = false)
    private int falsePositives;

    @Column(name = "false_negatives", nullable = false)
    private int falseNegatives;

    // Initial attempt (before any repair)
    @Column(name = "initial_patch_apply_success", nullable = false)
    private boolean initialPatchApplySuccess;

    @Column(name = "initial_compile_success", nullable = false)
    private boolean initialCompileSuccess;

    @Column(name = "initial_test_success", nullable = false)
    private boolean initialTestSuccess;

    // Final outcome (after bounded repair)
    @Column(name = "final_patch_apply_success", nullable = false)
    private boolean finalPatchApplySuccess;

    @Column(name = "final_compile_success", nullable = false)
    private boolean finalCompileSuccess;

    @Column(name = "final_test_success", nullable = false)
    private boolean finalTestSuccess;

    /** How many repair attempts were made (0 = first attempt passed). */
    @Column(name = "repair_count", nullable = false)
    private int repairCount;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "token_usage", nullable = false)
    private int tokenUsage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public EvaluationCaseResult() {}

    public EvaluationCaseResult(UUID runId, String caseId, String detectedFindings,
                                 int truePositives, int falsePositives, int falseNegatives,
                                 boolean initialPatchApplySuccess, boolean initialCompileSuccess,
                                 boolean initialTestSuccess,
                                 boolean finalPatchApplySuccess, boolean finalCompileSuccess,
                                 boolean finalTestSuccess, int repairCount,
                                 long latencyMs, int tokenUsage) {
        this.runId = runId;
        this.caseId = caseId;
        this.detectedFindings = detectedFindings;
        this.truePositives = truePositives;
        this.falsePositives = falsePositives;
        this.falseNegatives = falseNegatives;
        this.initialPatchApplySuccess = initialPatchApplySuccess;
        this.initialCompileSuccess = initialCompileSuccess;
        this.initialTestSuccess = initialTestSuccess;
        this.finalPatchApplySuccess = finalPatchApplySuccess;
        this.finalCompileSuccess = finalCompileSuccess;
        this.finalTestSuccess = finalTestSuccess;
        this.repairCount = repairCount;
        this.latencyMs = latencyMs;
        this.tokenUsage = tokenUsage;
    }

    public UUID getId()                        { return id; }
    public UUID getRunId()                     { return runId; }
    public String getCaseId()                  { return caseId; }
    public String getDetectedFindings()        { return detectedFindings; }
    public int getTruePositives()              { return truePositives; }
    public int getFalsePositives()             { return falsePositives; }
    public int getFalseNegatives()             { return falseNegatives; }
    public boolean isInitialPatchApplySuccess(){ return initialPatchApplySuccess; }
    public boolean isInitialCompileSuccess()   { return initialCompileSuccess; }
    public boolean isInitialTestSuccess()      { return initialTestSuccess; }
    public boolean isFinalPatchApplySuccess()  { return finalPatchApplySuccess; }
    public boolean isFinalCompileSuccess()     { return finalCompileSuccess; }
    public boolean isFinalTestSuccess()        { return finalTestSuccess; }
    public int getRepairCount()                { return repairCount; }
    public long getLatencyMs()                 { return latencyMs; }
    public int getTokenUsage()                 { return tokenUsage; }
    public Instant getCreatedAt()              { return createdAt; }
}
