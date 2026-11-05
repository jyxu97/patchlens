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
 * GET  /api/eval/runs/{id}/export → full reproducibility export
 * GET  /api/eval/runs            → list all runs with aggregate metrics
 * GET  /api/eval/runs/{id}       → detailed results for one run
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvaluationService evaluationService;
    private final EvaluationRunRepository runRepository;
    private final EvaluationCaseResultRepository caseResultRepository;

    private final tools.jackson.databind.ObjectMapper objectMapper;

    public EvalController(EvaluationService evaluationService,
                          EvaluationRunRepository runRepository,
                          EvaluationCaseResultRepository caseResultRepository,
                          tools.jackson.databind.ObjectMapper objectMapper) {
        this.evaluationService = evaluationService;
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Triggers an async evaluation run over the configured dataset (patchlens.eval.dataset).
     * Model, prompt version and inference settings are taken from the running configuration and
     * recorded on the run — they cannot be overridden by the request.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> triggerRun() {
        EvaluationRun run = evaluationService.createRun();
        evaluationService.runAsync(run.getId());
        return ResponseEntity.accepted().body(Map.of(
                "runId", run.getId().toString(),
                "status", "RUNNING",
                "aiMode", String.valueOf(run.getAiMode()),
                "message", "Evaluation started asynchronously"
        ));
    }

    /**
     * Full, self-describing export of one run for archiving/reproduction: run metadata (dataset
     * version + fingerprint, prompt fingerprint, inference config), aggregate metrics, and per-case
     * expected findings, model findings, matches, initial/final patches and validation outcomes.
     */
    @GetMapping("/runs/{id}/export")
    public ResponseEntity<Map<String, Object>> exportRun(@PathVariable UUID id) {
        return runRepository.findById(id)
                .map(run -> {
                    Map<String, Object> out = toRunSummary(run);
                    out.put("inferenceConfig", parse(run.getInferenceConfigJson()));
                    out.put("cases", caseResultRepository.findByRunId(id).stream().map(r -> {
                        Map<String, Object> m = toCaseSummary(r);
                        m.put("detectedFindings", parse(r.getDetectedFindings()));
                        m.put("expectedFindings", parse(r.getExpectedFindingsJson()));
                        m.put("matches", parse(r.getMatchesJson()));
                        m.put("patchResults", parse(r.getPatchResultsJson()));
                        m.put("error", r.getErrorMessage());
                        return m;
                    }).toList());
                    return ResponseEntity.ok(out);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private Object parse(String json) {
        if (json == null) return null;
        try { return objectMapper.readValue(json, Object.class); } catch (Exception e) { return json; }
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
        m.put("status", run.getStatus());
        m.put("errorMessage", run.getErrorMessage());
        m.put("aiMode", run.getAiMode());
        m.put("datasetFingerprint", run.getDatasetFingerprint());
        m.put("promptFingerprint", run.getPromptFingerprint());
        m.put("truePositives", run.getTruePositives());
        m.put("falsePositives", run.getFalsePositives());
        m.put("falseNegatives", run.getFalseNegatives());
        m.put("expectedCount", run.getExpectedCount());
        m.put("detectedCount", run.getDetectedCount());
        m.put("patchTargetCount", run.getPatchTargetCount());
        m.put("patchAttemptedCount", run.getPatchAttemptedCount());
        m.put("initialPatchPassCount", run.getInitialPatchPassCount());
        m.put("finalPatchPassCount", run.getFinalPatchPassCount());
        m.put("initialPatchPassRate", run.getInitialPatchPassRate());
        m.put("finalPatchPassRate", run.getFinalPatchPassRate());
        m.put("patchPassImprovementPp", run.getPatchPassImprovementPp());
        m.put("sandboxAvailable", run.getSandboxAvailable());
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
