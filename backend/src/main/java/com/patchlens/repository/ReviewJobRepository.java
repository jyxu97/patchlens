package com.patchlens.repository;

import com.patchlens.model.CommentStatus;
import com.patchlens.model.JobStatus;
import com.patchlens.model.ReviewJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    Optional<ReviewJob> findFirstByRepositoryOwnerAndRepositoryNameAndPullRequestNumberAndHeadSha(
            String repositoryOwner,
            String repositoryName,
            int pullRequestNumber,
            String headSha
    );

    /** Row-locked read (SELECT … FOR UPDATE): the basis of every atomic state transition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ReviewJob j where j.id = :id")
    Optional<ReviewJob> lockById(@Param("id") UUID id);

    /**
     * Jobs whose queue message may be missing:
     * PENDING jobs never published (or published long ago without being picked up), and
     * in-progress jobs whose worker lease expired (worker crashed) and were not republished recently.
     */
    @Query("""
            select j.id from ReviewJob j
            where (j.status = :pending and (j.publishedAt is null or j.publishedAt < :pendingStaleBefore))
               or (j.status in :inProgress and j.leaseExpiresAt < :now
                   and (j.publishedAt is null or j.publishedAt < :republishBefore))
            order by j.createdAt
            """)
    List<UUID> findRecoverable(@Param("pending") JobStatus pending,
                               @Param("inProgress") Collection<JobStatus> inProgress,
                               @Param("now") Instant now,
                               @Param("pendingStaleBefore") Instant pendingStaleBefore,
                               @Param("republishBefore") Instant republishBefore,
                               Pageable page);

    /** Jobs whose comment is due for (re)delivery, including deliveries abandoned mid-attempt. */
    @Query("""
            select j.id from ReviewJob j
            where (j.commentStatus in :retryable and (j.commentNextAttemptAt is null or j.commentNextAttemptAt <= :now))
               or (j.commentStatus = :delivering and j.commentLeaseUntil < :now)
            order by j.updatedAt
            """)
    List<UUID> findCommentsDue(@Param("retryable") Collection<CommentStatus> retryable,
                               @Param("delivering") CommentStatus delivering,
                               @Param("now") Instant now,
                               Pageable page);

    long countByStatus(JobStatus status);

    long countByCommentStatus(CommentStatus status);

    @Query("select min(j.createdAt) from ReviewJob j where j.status = :status")
    Instant oldestCreatedAtWithStatus(@Param("status") JobStatus status);
}
