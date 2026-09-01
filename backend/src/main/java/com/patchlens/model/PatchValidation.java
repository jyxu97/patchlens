package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patch_validations",
       indexes = @Index(name = "idx_patch_validations_patch_id", columnList = "patch_id"))
public class PatchValidation {

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
}
