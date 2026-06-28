package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a named (version-tagged) combination of system prompt + model.
 * Every non-cached analysis run records which PromptVersion was active,
 * enabling queries like "show average grounding rate by prompt version."
 */
@Entity
@Table(name = "prompt_versions")
public class PromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Short human-readable tag, e.g. "v1", "v2-gpt4o".
     * Unique — bumping this in application.properties creates a new row.
     */
    @Column(name = "version_tag", unique = true, nullable = false)
    private String versionTag;

    /** Model identifier, e.g. "gpt-4o-mini", "gpt-4o", "mock". */
    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** Optional human-readable description of what changed in this version. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { this.createdAt = Instant.now(); }

    public PromptVersion() {}

    public PromptVersion(String versionTag, String modelName, String notes) {
        this.versionTag = versionTag;
        this.modelName = modelName;
        this.notes = notes;
    }

    public UUID getId()           { return id; }
    public String getVersionTag() { return versionTag; }
    public String getModelName()  { return modelName; }
    public String getNotes()      { return notes; }
    public Instant getCreatedAt() { return createdAt; }
}
