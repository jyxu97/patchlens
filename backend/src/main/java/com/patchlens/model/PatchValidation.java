package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One validation attempt of one patch (the initial candidate or a repaired one).
 * A patch with two repairs has three rows, ordered by {@code attemptNumber} (0 = initial).
 *
 * <p>Columns added after the initial schema are nullable so that {@code ddl-auto=update} can
 * add them to tables that already contain rows.
 */
@Entity
@Table(name = "patch_validations",
       indexes = @Index(name = "idx_patch_validations_patch_id", columnList = "patch_id"))
public class PatchValidation {

    /** Which step decided the outcome. */
    public enum Stage { POLICY, SNAPSHOT, CONFIG, PATCH_APPLY, DEPENDENCIES, COMPILE, STATIC_ANALYSIS, TEST, SANDBOX, NONE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "patch_id", nullable = false)
    private UUID patchId;

    @Column(name = "patch_applied", nullable = false)
    private boolean patchApplied;

    @Column(name = "compile_passed", nullable = false)
    private boolean compilePassed;

    @Column(name = "static_analysis_passed", nullable = false)
    private boolean staticAnalysisPassed;

    @Column(name = "tests_passed", nullable = false)
    private boolean testsPassed;

    @Column(columnDefinition = "TEXT")
    private String logs;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ---- attempt metadata ----

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ValidationStatus status;

    @Column(name = "attempt_number")
    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage")
    private Stage failureStage;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    /** Commit SHA (or fixture revision) the patch was validated against. */
    @Column(name = "revision")
    private String revision;

    @Column(name = "config_source")
    private String configSource;

    @Column(name = "compile_ran")
    private Boolean compileRan;

    @Column(name = "static_analysis_ran")
    private Boolean staticAnalysisRan;

    @Column(name = "tests_ran")
    private Boolean testsRan;

    @Column(name = "timed_out")
    private Boolean timedOut;

    /** Exact patch text validated in this attempt (patch rows are overwritten on repair). */
    @Column(name = "patch_text", columnDefinition = "TEXT")
    private String patchText;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public PatchValidation() {}

    public PatchValidation(UUID patchId, boolean patchApplied, boolean compilePassed,
                           boolean staticAnalysisPassed, boolean testsPassed,
                           String logs, long durationMs) {
        this.patchId = patchId;
        this.patchApplied = patchApplied;
        this.compilePassed = compilePassed;
        this.staticAnalysisPassed = staticAnalysisPassed;
        this.testsPassed = testsPassed;
        this.logs = logs;
        this.durationMs = durationMs;
    }

    public UUID getId()                    { return id; }
    public UUID getPatchId()               { return patchId; }
    public boolean isPatchApplied()        { return patchApplied; }
    public boolean isCompilePassed()       { return compilePassed; }
    public boolean isStaticAnalysisPassed() { return staticAnalysisPassed; }
    public boolean isTestsPassed()         { return testsPassed; }
    public String getLogs()                { return logs; }
    public long getDurationMs()            { return durationMs; }
    public Instant getCreatedAt()          { return createdAt; }
    public ValidationStatus getStatus()    { return status; }
    public Integer getAttemptNumber()      { return attemptNumber; }
    public Stage getFailureStage()         { return failureStage; }
    public String getFailureReason()       { return failureReason; }
    public String getRevision()            { return revision; }
    public String getConfigSource()        { return configSource; }
    public boolean isCompileRan()          { return Boolean.TRUE.equals(compileRan); }
    public boolean isStaticAnalysisRan()   { return Boolean.TRUE.equals(staticAnalysisRan); }
    public boolean isTestsRan()            { return Boolean.TRUE.equals(testsRan); }
    public boolean isTimedOut()            { return Boolean.TRUE.equals(timedOut); }
    public String getPatchText()           { return patchText; }

    public void setPatchApplied(boolean v)          { this.patchApplied = v; }
    public void setCompilePassed(boolean v)         { this.compilePassed = v; }
    public void setStaticAnalysisPassed(boolean v)  { this.staticAnalysisPassed = v; }
    public void setTestsPassed(boolean v)           { this.testsPassed = v; }
    public void setLogs(String logs)                { this.logs = logs; }
    public void setDurationMs(long durationMs)      { this.durationMs = durationMs; }
    public void setStatus(ValidationStatus status)  { this.status = status; }
    public void setAttemptNumber(Integer n)         { this.attemptNumber = n; }
    public void setFailureStage(Stage stage)        { this.failureStage = stage; }
    public void setFailureReason(String reason)     { this.failureReason = reason; }
    public void setRevision(String revision)        { this.revision = revision; }
    public void setConfigSource(String src)         { this.configSource = src; }
    public void setCompileRan(boolean v)            { this.compileRan = v; }
    public void setStaticAnalysisRan(boolean v)     { this.staticAnalysisRan = v; }
    public void setTestsRan(boolean v)              { this.testsRan = v; }
    public void setTimedOut(boolean v)              { this.timedOut = v; }
    public void setPatchText(String patchText)      { this.patchText = patchText; }
    public void setPatchId(UUID patchId)            { this.patchId = patchId; }
}
