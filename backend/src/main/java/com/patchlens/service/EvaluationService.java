package com.patchlens.service;

import com.patchlens.model.*;
import com.patchlens.repository.EvaluationCaseResultRepository;
import com.patchlens.repository.EvaluationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * Offline evaluation harness.
 *
 * Loads curated eval cases from {@code src/test/resources/eval/cases/},
 * runs the full detection + patch + validation pipeline, matches detected findings
 * against expected findings, and persists precision/recall metrics.
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private static final String PIPELINE_VERSION = "2.0";
    private static final String DATASET_VERSION  = "v1";

    /** Classpath locations of eval case JSON files. */
    private static final List<String> EVAL_CASE_PATHS = List.of(
            "eval/cases/auth-001.json",
            "eval/cases/cache-001.json",
            "eval/cases/payment-001.json"
    );

    private final SamplePrLoader samplePrLoader;
    private final RiskScoringService riskScoringService;
    private final IssueDetectionService issueDetectionService;
    private final PatchGenerationService patchGenerationService;
    private final PatchValidationService patchValidationService;
    private final EvaluationRunRepository runRepository;
    private final EvaluationCaseResultRepository caseResultRepository;
    private final ObjectMapper objectMapper;

    public EvaluationService(SamplePrLoader samplePrLoader,
                             RiskScoringService riskScoringService,
                             IssueDetectionService issueDetectionService,
                             PatchGenerationService patchGenerationService,
                             PatchValidationService patchValidationService,
                             EvaluationRunRepository runRepository,
                             EvaluationCaseResultRepository caseResultRepository,
                             ObjectMapper objectMapper) {
        this.samplePrLoader = samplePrLoader;
        this.riskScoringService = riskScoringService;
        this.issueDetectionService = issueDetectionService;
        this.patchGenerationService = patchGenerationService;
        this.patchValidationService = patchValidationService;
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Triggers an async evaluation run.
     *
     * @param modelName  model name to record (e.g. "gpt-4o-mini")
     * @param promptVersion prompt version tag
     * @return the created (not yet complete) EvaluationRun
     */
    @Async
    public void runAsync(UUID runId) {
        EvaluationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("EvaluationRun not found: " + runId));
        execute(run);
    }

    public EvaluationRun createRun(String modelName, String promptVersion) {
        EvaluationRun run = new EvaluationRun(PIPELINE_VERSION, modelName, promptVersion, DATASET_VERSION);
        return runRepository.save(run);
    }

    // =====================================================================
    // Core evaluation loop
    // =====================================================================

    private void execute(EvaluationRun run) {
        List<EvalCase> cases = loadEvalCases();

        int totalTp = 0, totalFp = 0, totalFn = 0;
        int totalInitialCompile = 0, totalFinalCompile = 0;
        int totalInitialTest = 0,    totalFinalTest = 0;
        int totalPatchApply = 0, totalValidations = 0;

        for (EvalCase ec : cases) {
            try {
                EvaluationCaseResult result = evaluateCase(run.getId(), ec);
                caseResultRepository.save(result);

                totalTp += result.getTruePositives();
                totalFp += result.getFalsePositives();
                totalFn += result.getFalseNegatives();

                if (result.isFinalPatchApplySuccess()) totalPatchApply++;
                if (result.isInitialCompileSuccess())  totalInitialCompile++;
                if (result.isFinalCompileSuccess())    totalFinalCompile++;
                if (result.isInitialTestSuccess())     totalInitialTest++;
                if (result.isFinalTestSuccess())       totalFinalTest++;
                totalValidations++;

            } catch (Exception e) {
                log.error("Eval case {} failed: {}", ec.caseId(), e.getMessage(), e);
            }
        }

        double precision           = rate(totalTp, totalTp + totalFp);
        double recall              = rate(totalTp, totalTp + totalFn);
        double patchApplyRate      = rate(totalPatchApply, totalValidations);
        double initialCompileRate  = rate(totalInitialCompile, totalValidations);
        double finalCompileRate    = rate(totalFinalCompile, totalValidations);
        double initialTestRate     = rate(totalInitialTest, totalValidations);
        double finalTestRate       = rate(totalFinalTest, totalValidations);

        run.setPrecisionScore(precision);
        run.setRecallScore(recall);
        run.setPatchApplyRate(patchApplyRate);
        run.setInitialCompileSuccessRate(initialCompileRate);
        run.setCompileSuccessRate(finalCompileRate);
        run.setInitialTestPassRate(initialTestRate);
        run.setTestPassRate(finalTestRate);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);

        log.info("Eval run {} — precision={} recall={} compile: {}→{} test: {}→{}",
                run.getId(),
                pct(precision), pct(recall),
                pct(initialCompileRate), pct(finalCompileRate),
                pct(initialTestRate), pct(finalTestRate));
    }

    /**
     * Evaluates one case with the full initial → validate → repair → validate flow.
     *
     * When the eval case supplies {@code sourceFiles}, each validation step runs real
     * Docker compile + test against those files. Without sourceFiles, Docker writes empty
     * files (compile always fails) — only recall metrics are meaningful in that configuration.
     */
    private EvaluationCaseResult evaluateCase(UUID runId, EvalCase ec) {
        long start = System.currentTimeMillis();

        SamplePrLoader.SamplePr sample = samplePrLoader.load(ec.sampleId());
        List<RiskScore> riskScores = riskScoringService.score(sample.files());

        UUID syntheticJobId = UUID.randomUUID();
        List<ReviewFinding> detected = issueDetectionService.detect(
                syntheticJobId, sample.metadata(), sample.files(), riskScores);

        // Precision / recall matching
        int tp = 0, fp = 0;
        for (ReviewFinding df : detected) {
            if (isMatch(df, ec.expectedFindings())) tp++; else fp++;
        }
        int fn = Math.max(0, ec.expectedFindings().size() - tp);

        // Find the first HIGH/MEDIUM finding that has a matching changed file — same eligibility
        // rule as PatchGenerationService so eval exercises the real codepath
        ReviewFinding targetFinding = detected.stream()
                .filter(f -> f.getSeverity() == com.patchlens.model.FindingSeverity.HIGH
                          || f.getSeverity() == com.patchlens.model.FindingSeverity.MEDIUM)
                .filter(f -> sample.files().stream()
                        .anyMatch(cf -> cf.filename().equals(f.getFilePath())))
                .findFirst().orElse(null);

        boolean initApply = false, initCompile = false, initTest = false;
        boolean finalApply = false, finalCompile = false, finalTest = false;
        int repairCount = 0;

        if (targetFinding != null) {
            com.patchlens.model.ChangedFile targetFile = sample.files().stream()
                    .filter(cf -> cf.filename().equals(targetFinding.getFilePath()))
                    .findFirst().orElseThrow();

            ValidationConfig config = ValidationConfig.defaults();

            // Step 1 — Generate initial patch (no repair)
            PatchSuggestion initial = patchGenerationService.generateSinglePatch(
                    targetFinding, targetFile, List.of());

            // Step 2 — Validate initial patch
            PatchValidation initPv = ec.sourceFiles().isEmpty()
                    ? patchValidationService.validate(initial, sample.files(), config)
                    : patchValidationService.validate(initial, ec.sourceFiles(), config);
            initApply   = initPv.isPatchApplied();
            initCompile = initPv.isCompilePassed();
            initTest    = initPv.isTestsPassed();

            // Step 3 — Repair loop (bounded by MAX_REPAIR_ATTEMPTS)
            PatchSuggestion current = initial;
            boolean passed = initCompile && initTest;
            while (!passed && repairCount < PatchGenerationService.MAX_REPAIR_ATTEMPTS) {
                String errorFeedback = buildErrorFeedback(initPv);
                current = patchGenerationService.repairSinglePatch(
                        targetFinding, current, errorFeedback);
                repairCount++;

                PatchValidation repairPv = ec.sourceFiles().isEmpty()
                        ? patchValidationService.validate(current, sample.files(), config)
                        : patchValidationService.validate(current, ec.sourceFiles(), config);
                finalApply   = repairPv.isPatchApplied();
                finalCompile = repairPv.isCompilePassed();
                finalTest    = repairPv.isTestsPassed();
                passed = finalCompile && finalTest;
                initPv = repairPv; // use last result for next error feedback
            }

            // If first attempt passed, final == initial
            if (repairCount == 0) {
                finalApply   = initApply;
                finalCompile = initCompile;
                finalTest    = initTest;
            }
        }

        long latency = System.currentTimeMillis() - start;
        return new EvaluationCaseResult(
                runId, ec.caseId(), serializeFindings(detected),
                tp, fp, fn,
                initApply, initCompile, initTest,
                finalApply, finalCompile, finalTest,
                repairCount, latency, 0
        );
    }

    private String buildErrorFeedback(PatchValidation pv) {
        if (!pv.isPatchApplied())   return "Patch did not apply cleanly. " + truncateLogs(pv.getLogs());
        if (!pv.isCompilePassed())  return "Compile failed. " + truncateLogs(pv.getLogs());
        if (!pv.isTestsPassed())    return "Tests failed. " + truncateLogs(pv.getLogs());
        return "";
    }

    private String truncateLogs(String logs) {
        if (logs == null) return "";
        return logs.length() > 2000 ? logs.substring(0, 2000) + "...(truncated)" : logs;
    }

    private static double rate(int num, int denom) {
        return denom > 0 ? (double) num / denom : 0.0;
    }

    private static String pct(double r) {
        return String.format("%.0f%%", r * 100);
    }

    /**
     * A finding matches an expected finding if category matches and the file matches.
     */
    private boolean isMatch(ReviewFinding detected, List<ExpectedFinding> expected) {
        for (ExpectedFinding ef : expected) {
            if (ef.category().name().equalsIgnoreCase(detected.getCategory().name())
                    && detected.getFilePath().contains(fileBaseName(ef.file()))) {
                return true;
            }
        }
        return false;
    }

    private String fileBaseName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    // =====================================================================
    // Eval case loading
    // =====================================================================

    record ExpectedFinding(FindingCategory category, String file, int[] lineRange, String description) {}

    /**
     * @param sourceFiles optional map of repo-relative path → file content; when present, Docker
     *                    validation writes these files to the sandbox instead of empty stubs.
     *                    Leave empty to measure recall only (compile metrics will be 0%).
     */
    record EvalCase(String caseId, String sampleId, String description,
                    List<ExpectedFinding> expectedFindings,
                    java.util.Map<String, String> sourceFiles) {}

    private List<EvalCase> loadEvalCases() {
        List<EvalCase> result = new ArrayList<>();
        for (String path : EVAL_CASE_PATHS) {
            try {
                ClassPathResource resource = new ClassPathResource(path);
                if (!resource.exists()) {
                    log.warn("Eval case not found on classpath: {}", path);
                    continue;
                }
                try (InputStream is = resource.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
                    String caseId   = root.path("caseId").asString("");
                    String sampleId = root.path("sampleId").asString("");
                    String desc     = root.path("description").asString("");

                    List<ExpectedFinding> expected = new ArrayList<>();
                    for (JsonNode fn : root.path("expectedFindings")) {
                        String catStr = fn.path("category").asString("MAINTAINABILITY");
                        FindingCategory cat;
                        try { cat = FindingCategory.valueOf(catStr); }
                        catch (Exception e) { cat = FindingCategory.MAINTAINABILITY; }

                        JsonNode lr = fn.path("lineRange");
                        int[] lineRange = (lr.isArray() && lr.size() >= 2)
                                ? new int[]{lr.get(0).asInt(1), lr.get(1).asInt(999)}
                                : new int[]{1, 999};

                        expected.add(new ExpectedFinding(
                                cat,
                                fn.path("file").asString(""),
                                lineRange,
                                fn.path("description").asString("")
                        ));
                    }
                    // Parse optional sourceFiles map (path → content)
                    java.util.Map<String, String> sourceFiles = new java.util.LinkedHashMap<>();
                    JsonNode sfNode = root.path("sourceFiles");
                    if (sfNode.isObject()) {
                        sfNode.properties().forEach(e ->
                                sourceFiles.put(e.getKey(), e.getValue().asString("")));
                    }

                    result.add(new EvalCase(caseId, sampleId, desc, expected, sourceFiles));
                }
            } catch (Exception e) {
                log.error("Failed to load eval case {}: {}", path, e.getMessage(), e);
            }
        }
        return result;
    }

    private String serializeFindings(List<ReviewFinding> findings) {
        try {
            List<Map<String, Object>> list = findings.stream().map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file", f.getFilePath());
                m.put("category", f.getCategory().name());
                m.put("severity", f.getSeverity().name());
                m.put("title", f.getTitle());
                m.put("confidence", f.getConfidence());
                return m;
            }).toList();
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
