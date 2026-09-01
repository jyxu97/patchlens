package com.patchlens.repository;

import com.patchlens.model.ReviewFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ReviewFindingRepository extends JpaRepository<ReviewFinding, UUID> {

    List<ReviewFinding> findByReviewJobId(UUID reviewJobId);

    @Query("SELECT COUNT(f) FROM ReviewFinding f")
    long countTotal();

    @Query("SELECT COALESCE(AVG(CAST((SELECT COUNT(f2) FROM ReviewFinding f2 WHERE f2.reviewJobId = f.reviewJobId) AS double)), 0) FROM ReviewFinding f")
    double avgFindingsPerJob();
}
