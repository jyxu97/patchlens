package com.patchlens.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks the state of a repository's context index.
 * One row per repository; updated every time the repo is re-indexed.
 *
 * indexed_tree_sha stores the GitHub root tree SHA at index time.
 * Staleness is detected by comparing this SHA against the current
 * default-branch tree SHA fetched from the GitHub API.
 */
@Entity
@Table(name = "repo_index_metadata")
public class RepoIndexMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** "owner/repo" — unique identifier for the repository. */
    @Column(name = "repo_full_name", nullable = false, unique = true)
    private String repoFullName;

    /** Default branch at index time (e.g. "main", "master"). */
    @Column(name = "base_branch", nullable = false)
    private String baseBranch;

    /** Root tree SHA of the default branch at index time. Used for staleness detection. */
    @Column(name = "indexed_tree_sha", nullable = false)
    private String indexedTreeSha;

    /** Number of files whose content was fetched and embedded. */
    @Column(name = "files_indexed", nullable = false)
    private int filesIndexed;

    /**
     * True if the GitHub tree response was truncated (repo > ~100k files).
     * In that case, indexing covers only the files returned by the API.
     */
    @Column(name = "partial_index", nullable = false)
    private boolean partialIndex;

    @Column(name = "indexed_at", nullable = false, updatable = false)
    private Instant indexedAt;

    public RepoIndexMetadata() {}

    public RepoIndexMetadata(String repoFullName, String baseBranch, String indexedTreeSha,
                              int filesIndexed, boolean partialIndex) {
        this.repoFullName = repoFullName;
        this.baseBranch = baseBranch;
        this.indexedTreeSha = indexedTreeSha;
        this.filesIndexed = filesIndexed;
        this.partialIndex = partialIndex;
        this.indexedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getRepoFullName() { return repoFullName; }
    public String getBaseBranch() { return baseBranch; }
    public String getIndexedTreeSha() { return indexedTreeSha; }
    public int getFilesIndexed() { return filesIndexed; }
    public boolean isPartialIndex() { return partialIndex; }
    public Instant getIndexedAt() { return indexedAt; }
}
