package com.patchlens.service;

import com.patchlens.model.PromptVersion;
import com.patchlens.repository.PromptVersionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ensures a {@link PromptVersion} row exists for the currently configured
 * prompt version tag + model name, and vends it to other services.
 *
 * <p>On startup, calls {@code getOrCreate()} so the row is always present
 * before any analysis run tries to reference it.
 */
@Service
public class PromptVersionService {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionService.class);

    private final PromptVersionRepository repository;
    private final String versionTag;
    private final String modelName;
    private final String notes;

    /** Cached reference — set once during {@code @PostConstruct}. */
    private PromptVersion currentVersion;

    public PromptVersionService(
            PromptVersionRepository repository,
            @Value("${prompt.version:v1}") String versionTag,
            @Value("${openai.model:gpt-4o-mini}") String modelName,
            @Value("${prompt.notes:Initial version}") String notes) {
        this.repository = repository;
        this.versionTag = versionTag;
        this.modelName = modelName;
        this.notes = notes;
    }

    /**
     * Registers the current prompt version on startup.
     * If the version tag already exists in the DB, the existing row is reused.
     * If it's new, a fresh row is inserted.
     */
    @PostConstruct
    void init() {
        try {
            currentVersion = getOrCreate();
            log.info("Active prompt version: {} (model={})", currentVersion.getVersionTag(), currentVersion.getModelName());
        } catch (Exception e) {
            // DB may not be reachable in unit tests — degrade gracefully
            log.warn("Could not register prompt version (DB unavailable?): {}", e.getMessage());
        }
    }

    /**
     * Returns the {@link PromptVersion} for the currently configured tag,
     * inserting a new row if it doesn't exist yet.
     */
    public PromptVersion getOrCreate() {
        return repository.findByVersionTag(versionTag)
                .orElseGet(() -> repository.save(new PromptVersion(versionTag, modelName, notes)));
    }

    /**
     * Returns the cached current version (populated at startup).
     * May be {@code null} if the DB was unreachable at startup.
     */
    public PromptVersion getCurrentVersion() {
        return currentVersion;
    }
}
