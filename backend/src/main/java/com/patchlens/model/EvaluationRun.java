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

    @Column(name = "compile_success_rate")
    private Double compileSuccessRate;

    @Column(name = "test_pass_rate")
    private Double testPassRate;

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
    public Double getPatchApplyRate()     { return patchApplyRate; }
    public Double getCompileSuccessRate() { return compileSuccessRate; }
    public Double getTestPassRate()       { return testPassRate; }

    public void setCompletedAt(Instant completedAt)         { this.completedAt = completedAt; }
    public void setPrecisionScore(Double precisionScore)     { this.precisionScore = precisionScore; }
    public void setRecallScore(Double recallScore)           { this.recallScore = recallScore; }
    public void setPatchApplyRate(Double patchApplyRate)     { this.patchApplyRate = patchApplyRate; }
    public void setCompileSuccessRate(Double r)              { this.compileSuccessRate = r; }
    public void setTestPassRate(Double testPassRate)         { this.testPassRate = testPassRate; }
}
