package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patch_suggestions",
       indexes = @Index(name = "idx_patch_suggestions_finding_id", columnList = "finding_id"))
public class PatchSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "finding_id", nullable = false)
    private UUID findingId;

    @Column(name = "patch_text", columnDefinition = "TEXT", nullable = false)
    private String patchText;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ValidationStatus status = ValidationStatus.PENDING;

    @Column(name = "repair_attempts", nullable = false)
    private int repairAttempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public PatchSuggestion() {}

    public PatchSuggestion(UUID findingId, String patchText, String rationale) {
        this.findingId = findingId;
        this.patchText = patchText;
        this.rationale = rationale;
        this.status = ValidationStatus.PENDING;
        this.repairAttempts = 0;
    }

    public UUID getId()                  { return id; }
    public UUID getFindingId()           { return findingId; }
    public String getPatchText()         { return patchText; }
    public String getRationale()         { return rationale; }
    public ValidationStatus getStatus()  { return status; }
    public int getRepairAttempts()       { return repairAttempts; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }

    public void setStatus(ValidationStatus status)       { this.status = status; }
    public void setPatchText(String patchText)           { this.patchText = patchText; }
    public void setRationale(String rationale)           { this.rationale = rationale; }
    public void setRepairAttempts(int repairAttempts)    { this.repairAttempts = repairAttempts; }
}
