package com.patchlens.repository;

import com.patchlens.model.JobStatus;
import com.patchlens.model.ReviewJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewJobRepository extends JpaRepository<ReviewJob, UUID> {

    Optional<ReviewJob> findFirstByRepositoryOwnerAndRepositoryNameAndPullRequestNumberAndStatusIn(
            String repositoryOwner,
            String repositoryName,
            int pullRequestNumber,
            List<JobStatus> statuses
    );
}
