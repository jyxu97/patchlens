package com.patchlens.repository;

import com.patchlens.model.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    @Query("SELECT COUNT(a) FROM AnalysisRun a WHERE a.status = 'success'")
    long countSuccessful();

    @Query("SELECT COUNT(a) FROM AnalysisRun a WHERE a.cacheHit = true AND a.status = 'success'")
    long countCacheHits();

    @Query("SELECT COALESCE(AVG(a.totalLatencyMs), 0) FROM AnalysisRun a WHERE a.cacheHit = false AND a.status = 'success'")
    double avgCacheMissLatencyMs();

    @Query("SELECT COALESCE(AVG(a.totalLatencyMs), 0) FROM AnalysisRun a WHERE a.cacheHit = true AND a.status = 'success'")
    double avgCacheHitLatencyMs();
}
