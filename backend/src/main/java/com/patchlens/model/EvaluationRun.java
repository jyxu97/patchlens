package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per offline evaluation run.
 * Stores aggregate precision/recall and patch pipeline metrics.
 */
@Entity
@Table(name = "evaluation_runs")
public class EvaluationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "pipeline_version")
    private String pipelineVersion;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "dataset_version")
    private String datasetVersion;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "precision_score")
    private Double precisionScore;

    @Column(name = "recall_score")
    private Double recallScore;

    @Column(name = "patch_apply_rate")
    private Double patchApplyRate;

    /** Compile success rate on the FIRST generated patch, before any repair. */
    @Column(name = "initial_compile_success_rate")
    private Double initialCompileSuccessRate;

    /** Compile success rate after bounded repair — the headline "Z%" figure. */
    @Column(name = "compile_success_rate")
    private Double compileSuccessRate;

    /** Test pass rate on the first generated patch, before any repair. */
    @Column(name = "initial_test_pass_rate")
    private Double initialTestPassRate;

    @Column(name = "test_pass_rate")
    private Double testPassRate;


    // ---- reproducibility metadata (nullable: added after the initial schema) ----

    /** RUNNING, COMPLETED or FAILED. */
    @Column(name = "status")
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** "mock" or "openai" — mock runs are not model evaluations. */
    @Column(name = "ai_mode")
    private String aiMode;

    /** SHA-256 prefix over every dataset file (identifies the exact cases, fixtures and labels). */
    @Column(name = "dataset_fingerprint")
    private String datasetFingerprint;

    /** SHA-256 prefix over the prompt templates used. */
    @Column(name = "prompt_fingerprint")
    private String promptFingerprint;

    /** Model, temperature, max tokens, repair budget, sandbox image, matching rule, … */
    @Column(name = "inference_config_json", columnDefinition = "TEXT")
    private String inferenceConfigJson;

    @Column(name = "true_positives")       private Integer truePositives;
    @Column(name = "false_positives")      private Integer falsePositives;
    @Column(name = "false_negatives")      private Integer falseNegatives;
    @Column(name = "expected_count")       private Integer expectedCount;
    @Column(name = "detected_count")       private Integer detectedCount;

    /** Denominator of both patch pass rates: every labeled patch target in the dataset. */
    @Column(name = "patch_target_count")   private Integer patchTargetCount;
    @Column(name = "patch_attempted_count") private Integer patchAttemptedCount;
    @Column(name = "initial_patch_pass_count") private Integer initialPatchPassCount;
    @Column(name = "final_patch_pass_count")   private Integer finalPatchPassCount;
    @Column(name = "initial_patch_pass_rate")  private Double initialPatchPassRate;
    @Column(name = "final_patch_pass_rate")    private Double finalPatchPassRate;
    /** (final − initial) × 100, in percentage points. Null when validation did not run. */
    @Column(name = "patch_pass_improvement_pp") private Double patchPassImprovementPp;

    @Column(name = "sandbox_available")    private Boolean sandboxAvailable;

    @PrePersist
    protected void onCreate() {
        startedAt = Instant.now();
    }

    public EvaluationRun() {}

    public EvaluationRun(String pipelineVersion, String modelName,
                         String promptVersion, String datasetVersion) {
        this.pipelineVersion = pipelineVersion;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.datasetVersion = datasetVersion;
    }

    public UUID getId()                   { return id; }
    public String getPipelineVersion()    { return pipelineVersion; }
    public String getModelName()          { return modelName; }
    public String getPromptVersion()      { return promptVersion; }
    public String getDatasetVersion()     { return datasetVersion; }
    public Instant getStartedAt()         { return startedAt; }
    public Instant getCompletedAt()       { return completedAt; }
    public Double getPrecisionScore()     { return precisionScore; }
    public Double getRecallScore()        { return recallScore; }
    public Double getPatchApplyRate()              { return patchApplyRate; }
    public Double getInitialCompileSuccessRate()   { return initialCompileSuccessRate; }
    public Double getCompileSuccessRate()          { return compileSuccessRate; }
    public Double getInitialTestPassRate()         { return initialTestPassRate; }
    public Double getTestPassRate()                { return testPassRate; }

    public void setCompletedAt(Instant completedAt)                       { this.completedAt = completedAt; }
    public void setPrecisionScore(Double precisionScore)                   { this.precisionScore = precisionScore; }
    public void setRecallScore(Double recallScore)                         { this.recallScore = recallScore; }
    public void setPatchApplyRate(Double patchApplyRate)                   { this.patchApplyRate = patchApplyRate; }
    public void setInitialCompileSuccessRate(Double r)                     { this.initialCompileSuccessRate = r; }
    public void setCompileSuccessRate(Double r)                            { this.compileSuccessRate = r; }
    public void setInitialTestPassRate(Double r)                           { this.initialTestPassRate = r; }
    public void setTestPassRate(Double testPassRate)                       { this.testPassRate = testPassRate; }

    public String getStatus()                 { return status; }
    public String getErrorMessage()           { return errorMessage; }
    public String getAiMode()                 { return aiMode; }
    public String getDatasetFingerprint()     { return datasetFingerprint; }
    public String getPromptFingerprint()      { return promptFingerprint; }
    public String getInferenceConfigJson()    { return inferenceConfigJson; }
    public Integer getTruePositives()         { return truePositives; }
    public Integer getFalsePositives()        { return falsePositives; }
    public Integer getFalseNegatives()        { return falseNegatives; }
    public Integer getExpectedCount()         { return expectedCount; }
    public Integer getDetectedCount()         { return detectedCount; }
    public Integer getPatchTargetCount()      { return patchTargetCount; }
    public Integer getPatchAttemptedCount()   { return patchAttemptedCount; }
    public Integer getInitialPatchPassCount() { return initialPatchPassCount; }
    public Integer getFinalPatchPassCount()   { return finalPatchPassCount; }
    public Double getInitialPatchPassRate()   { return initialPatchPassRate; }
    public Double getFinalPatchPassRate()     { return finalPatchPassRate; }
    public Double getPatchPassImprovementPp() { return patchPassImprovementPp; }
    public Boolean getSandboxAvailable()      { return sandboxAvailable; }

    public void setStatus(String v)                 { this.status = v; }
    public void setErrorMessage(String v)           { this.errorMessage = v; }
    public void setAiMode(String v)                 { this.aiMode = v; }
    public void setDatasetVersion(String v)         { this.datasetVersion = v; }
    public void setDatasetFingerprint(String v)     { this.datasetFingerprint = v; }
    public void setPromptFingerprint(String v)      { this.promptFingerprint = v; }
    public void setInferenceConfigJson(String v)    { this.inferenceConfigJson = v; }
    public void setTruePositives(Integer v)         { this.truePositives = v; }
    public void setFalsePositives(Integer v)        { this.falsePositives = v; }
    public void setFalseNegatives(Integer v)        { this.falseNegatives = v; }
    public void setExpectedCount(Integer v)         { this.expectedCount = v; }
    public void setDetectedCount(Integer v)         { this.detectedCount = v; }
    public void setPatchTargetCount(Integer v)      { this.patchTargetCount = v; }
    public void setPatchAttemptedCount(Integer v)   { this.patchAttemptedCount = v; }
    public void setInitialPatchPassCount(Integer v) { this.initialPatchPassCount = v; }
    public void setFinalPatchPassCount(Integer v)   { this.finalPatchPassCount = v; }
    public void setInitialPatchPassRate(Double v)   { this.initialPatchPassRate = v; }
    public void setFinalPatchPassRate(Double v)     { this.finalPatchPassRate = v; }
    public void setPatchPassImprovementPp(Double v) { this.patchPassImprovementPp = v; }
    public void setSandboxAvailable(Boolean v)      { this.sandboxAvailable = v; }
}
