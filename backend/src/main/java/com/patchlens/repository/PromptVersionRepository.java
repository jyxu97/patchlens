package com.patchlens.repository;

import com.patchlens.model.PromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PromptVersionRepository extends JpaRepository<PromptVersion, UUID> {
    Optional<PromptVersion> findByVersionTag(String versionTag);
}
