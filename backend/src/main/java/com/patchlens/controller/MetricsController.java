package com.patchlens.controller;

import com.patchlens.repository.AnalysisRunRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final AnalysisRunRepository analysisRunRepository;

    public MetricsController(AnalysisRunRepository analysisRunRepository) {
        this.analysisRunRepository = analysisRunRepository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMetrics() {
        long total = analysisRunRepository.countSuccessful();
        long hits = analysisRunRepository.countCacheHits();
        double missAvg = analysisRunRepository.avgCacheMissLatencyMs();
        double hitAvg = analysisRunRepository.avgCacheHitLatencyMs();

        return ResponseEntity.ok(Map.of(
                "totalAnalyses", total,
                "cacheHitRate", total > 0 ? Math.round((double) hits / total * 100.0) / 100.0 : 0.0,
                "avgCacheMissLatencyMs", Math.round(missAvg),
                "avgCacheHitLatencyMs", Math.round(hitAvg)
        ));
    }
}
