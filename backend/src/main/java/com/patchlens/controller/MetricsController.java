package com.patchlens.controller;

import com.patchlens.repository.AnalysisRunRepository;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.repository.PatchValidationRepository;
import com.patchlens.repository.ReviewFindingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final AnalysisRunRepository analysisRunRepository;
    private final ReviewFindingRepository findingRepository;
    private final PatchSuggestionRepository patchSuggestionRepository;
    private final PatchValidationRepository patchValidationRepository;

    public MetricsController(AnalysisRunRepository analysisRunRepository,
                             ReviewFindingRepository findingRepository,
                             PatchSuggestionRepository patchSuggestionRepository,
                             PatchValidationRepository patchValidationRepository) {
        this.analysisRunRepository = analysisRunRepository;
        this.findingRepository = findingRepository;
        this.patchSuggestionRepository = patchSuggestionRepository;
        this.patchValidationRepository = patchValidationRepository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMetrics() {
        long total = analysisRunRepository.countSuccessful();
        long hits  = analysisRunRepository.countCacheHits();
        double missAvg = analysisRunRepository.avgCacheMissLatencyMs();
        double hitAvg  = analysisRunRepository.avgCacheHitLatencyMs();

        // Finding metrics
        long findingsTotal = findingRepository.countTotal();

        // Patch metrics
        long patchTotal  = patchSuggestionRepository.countTotal();
        double repairAvg = patchSuggestionRepository.avgRepairAttempts();

        // Validation metrics
        long valTotal   = patchValidationRepository.countTotal();
        long valApplied = patchValidationRepository.countPatchApplied();
        long valCompile = patchValidationRepository.countCompilePassed();
        long valTest    = patchValidationRepository.countTestsPassed();

        Map<String, Object> metrics = new LinkedHashMap<>();
        // Existing metrics
        metrics.put("totalAnalyses", total);
        metrics.put("cacheHitRate", total > 0 ? Math.round((double) hits / total * 100.0) / 100.0 : 0.0);
        metrics.put("avgCacheMissLatencyMs", Math.round(missAvg));
        metrics.put("avgCacheHitLatencyMs", Math.round(hitAvg));
        // New: finding metrics
        metrics.put("findingsTotal", findingsTotal);
        // New: patch generation metrics
        metrics.put("patchGeneratedTotal", patchTotal);
        metrics.put("patchApplySuccessRate", valTotal > 0 ? round2((double) valApplied / valTotal) : 0.0);
        // New: validation metrics
        metrics.put("compileSuccessRate", valTotal > 0 ? round2((double) valCompile / valTotal) : 0.0);
        metrics.put("testPassRate", valTotal > 0 ? round2((double) valTest / valTotal) : 0.0);
        // New: repair loop
        metrics.put("repairAttemptRate", patchTotal > 0 ? round2(repairAvg) : 0.0);

        return ResponseEntity.ok(metrics);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
