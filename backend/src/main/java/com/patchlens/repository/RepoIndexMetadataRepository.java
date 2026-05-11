package com.patchlens.repository;

import com.patchlens.model.RepoIndexMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RepoIndexMetadataRepository extends JpaRepository<RepoIndexMetadata, UUID> {

    Optional<RepoIndexMetadata> findByRepoFullName(String repoFullName);

    void deleteByRepoFullName(String repoFullName);
}
