package com.patchlens.controller;

import com.patchlens.model.EvaluationCaseResult;
import com.patchlens.model.EvaluationRun;
import com.patchlens.repository.EvaluationCaseResultRepository;
import com.patchlens.repository.EvaluationRunRepository;
import com.patchlens.service.EvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for triggering and inspecting offline evaluation runs.
 *
 * POST /api/eval/run             → trigger async evaluation run
 * GET  /api/eval/runs            → list all runs with aggregate metrics
 * GET  /api/eval/runs/{id}       → detailed results for one run
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvaluationService evaluationService;
    private final EvaluationRunRepository runRepository;
    private final EvaluationCaseResultRepository caseResultRepository;

    public EvalController(EvaluationService evaluationService,
                          EvaluationRunRepository runRepository,
                          EvaluationCaseResultRepository caseResultRepository) {
        this.evaluationService = evaluationService;
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
    }

    /**
     * Triggers an async evaluation run.
     * Request body (optional):
     *   { "modelName": "gpt-4o-mini", "promptVersion": "v1" }
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> triggerRun(
            @RequestBody(required = false) Map<String, String> body) {
        String modelName    = body != null ? body.getOrDefault("modelName", "mock") : "mock";
        String promptVersion = body != null ? body.getOrDefault("promptVersion", "v1") : "v1";

        EvaluationRun run = evaluationService.createRun(modelName, promptVersion);
        evaluationService.runAsync(run.getId());

        return ResponseEntity.accepted().body(Map.of(
                "runId", run.getId().toString(),
                "status", "RUNNING",
                "message", "Evaluation started asynchronously"
        ));
    }

    /**
     * Lists all evaluation runs with their aggregate metrics.
     */
    @GetMapping("/runs")
    public ResponseEntity<List<Map<String, Object>>> listRuns() {
        List<Map<String, Object>> runs = runRepository.findAll().stream()
                .map(this::toRunSummary)
                .toList();
        return ResponseEntity.ok(runs);
    }

    /**
     * Returns detailed results for one evaluation run, including per-case breakdown.
     */
    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> getRunDetail(@PathVariable UUID id) {
        return runRepository.findById(id)
                .map(run -> {
                    List<EvaluationCaseResult> cases = caseResultRepository.findByRunId(id);
                    Map<String, Object> detail = toRunSummary(run);
                    detail.put("cases", cases.stream().map(this::toCaseSummary).toList());
                    return ResponseEntity.ok(detail);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // =====================================================================
    // Response builders
    // =====================================================================

    private Map<String, Object> toRunSummary(EvaluationRun run) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("pipelineVersion", run.getPipelineVersion());
        m.put("modelName", run.getModelName());
        m.put("promptVersion", run.getPromptVersion());
        m.put("datasetVersion", run.getDatasetVersion());
        m.put("startedAt", run.getStartedAt());
        m.put("completedAt", run.getCompletedAt());
        m.put("precisionScore", run.getPrecisionScore());
        m.put("recallScore", run.getRecallScore());
        m.put("patchApplyRate", run.getPatchApplyRate());
        m.put("initialCompileSuccessRate", run.getInitialCompileSuccessRate());
        m.put("compileSuccessRate", run.getCompileSuccessRate());
        m.put("initialTestPassRate", run.getInitialTestPassRate());
        m.put("testPassRate", run.getTestPassRate());
        return m;
    }

    private Map<String, Object> toCaseSummary(EvaluationCaseResult r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("caseId", r.getCaseId());
        m.put("truePositives", r.getTruePositives());
        m.put("falsePositives", r.getFalsePositives());
        m.put("falseNegatives", r.getFalseNegatives());
        m.put("initialPatchApplySuccess", r.isInitialPatchApplySuccess());
        m.put("initialCompileSuccess", r.isInitialCompileSuccess());
        m.put("initialTestSuccess", r.isInitialTestSuccess());
        m.put("finalPatchApplySuccess", r.isFinalPatchApplySuccess());
        m.put("finalCompileSuccess", r.isFinalCompileSuccess());
        m.put("finalTestSuccess", r.isFinalTestSuccess());
        m.put("repairCount", r.getRepairCount());
        m.put("latencyMs", r.getLatencyMs());
        m.put("detectedFindings", r.getDetectedFindings());
        return m;
    }
}
