package com.patchlens.repository;

import com.patchlens.model.RepoIndexMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface RepoIndexMetadataRepository extends JpaRepository<RepoIndexMetadata, UUID> {

    Optional<RepoIndexMetadata> findByRepoFullName(String repoFullName);

    /** Bulk DELETE — safe to call from @Async threads. */
    @Modifying
    @Transactional
    @Query("DELETE FROM RepoIndexMetadata r WHERE r.repoFullName = :repoFullName")
    void deleteByRepoFullName(@Param("repoFullName") String repoFullName);
}
