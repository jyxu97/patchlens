package com.patchlens.service;

import com.patchlens.model.RepoIndexMetadata;
import com.patchlens.repository.RepoIndexMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Owns the per-repository "active index version" pointer. Switching it is a single-row update,
 * so readers see either the complete old index or the complete new one, never a mixture.
 */
@Service
public class RepoIndexStateService {

    private final RepoIndexMetadataRepository repository;

    public RepoIndexStateService(RepoIndexMetadataRepository repository) {
        this.repository = repository;
    }

    public Optional<RepoIndexMetadata> current(String owner, String repo) {
        return repository.findByRepoFullName(owner + "/" + repo);
    }

    /** Active version used for retrieval, or empty if the repository has no tracked index. */
    public Optional<String> activeVersion(String owner, String repo) {
        return current(owner, repo).map(RepoIndexMetadata::getIndexedTreeSha);
    }

    /** Atomically makes {@code treeSha} the active version (upsert on the unique repo name). */
    @Transactional
    public void activate(String owner, String repo, String branch, String treeSha, String commitSha,
                         int filesIndexed, boolean partial) {
        String name = owner + "/" + repo;
        RepoIndexMetadata meta = repository.findByRepoFullName(name)
                .orElseGet(() -> new RepoIndexMetadata(name, branch, treeSha, filesIndexed, partial));
        meta.activate(branch, treeSha, commitSha, filesIndexed, partial);
        repository.save(meta);
    }
}
