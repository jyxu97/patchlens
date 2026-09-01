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
        List<EvaluationCaseResult> caseResults = new ArrayList<>();

        int totalTp = 0, totalFp = 0, totalFn = 0;
        int totalPatchApply = 0, totalCompile = 0, totalTest = 0, totalValidations = 0;

        for (EvalCase ec : cases) {
            try {
                EvaluationCaseResult result = evaluateCase(run.getId(), ec);
                caseResultRepository.save(result);
                caseResults.add(result);

                totalTp += result.getTruePositives();
                totalFp += result.getFalsePositives();
                totalFn += result.getFalseNegatives();

                if (result.isPatchApplySuccess()) totalPatchApply++;
                if (result.isCompileSuccess())   totalCompile++;
                if (result.isTestSuccess())       totalTest++;
                totalValidations++;

            } catch (Exception e) {
                log.error("Eval case {} failed: {}", ec.caseId(), e.getMessage(), e);
            }
        }

        // Aggregate metrics
        double precision = (totalTp + totalFp) > 0
                ? (double) totalTp / (totalTp + totalFp) : 0.0;
        double recall    = (totalTp + totalFn) > 0
                ? (double) totalTp / (totalTp + totalFn) : 0.0;
        double patchApplyRate = totalValidations > 0
                ? (double) totalPatchApply / totalValidations : 0.0;
        double compileRate    = totalValidations > 0
                ? (double) totalCompile / totalValidations : 0.0;
        double testRate       = totalValidations > 0
                ? (double) totalTest / totalValidations : 0.0;

        run.setPrecisionScore(precision);
        run.setRecallScore(recall);
        run.setPatchApplyRate(patchApplyRate);
        run.setCompileSuccessRate(compileRate);
        run.setTestPassRate(testRate);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);

        log.info("Eval run {} complete — precision={:.2f} recall={:.2f} patch_apply={:.2f}",
                run.getId(), precision, recall, patchApplyRate);
    }

    private EvaluationCaseResult evaluateCase(UUID runId, EvalCase ec) {
        long start = System.currentTimeMillis();

        SamplePrLoader.SamplePr sample = samplePrLoader.load(ec.sampleId());
        List<RiskScore> riskScores = riskScoringService.score(sample.files());

        // Use a synthetic job ID for eval (no ReviewJob DB row needed)
        UUID syntheticJobId = UUID.randomUUID();
        List<ReviewFinding> detected = issueDetectionService.detect(
                syntheticJobId, sample.metadata(), sample.files(), riskScores);

        // Match findings
        int tp = 0, fp = 0;
        for (ReviewFinding df : detected) {
            if (isMatch(df, ec.expectedFindings())) tp++; else fp++;
        }
        int fn = Math.max(0, ec.expectedFindings().size() - tp);

        // Patch + validate (using defaults — no Docker in eval)
        List<PatchSuggestion> patches = patchGenerationService.generatePatches(
                detected, sample.files(), List.of());

        boolean patchApply = false, compile = false, test = false;
        if (!patches.isEmpty()) {
            PatchValidation pv = patchValidationService.validate(
                    patches.get(0), sample.files(), ValidationConfig.defaults());
            patchApply = pv.isPatchApplied();
            compile    = pv.isCompilePassed();
            test       = pv.isTestsPassed();
        }

        long latency = System.currentTimeMillis() - start;
        String detectedJson = serializeFindings(detected);

        return new EvaluationCaseResult(
                runId, ec.caseId(), detectedJson,
                tp, fp, fn,
                patchApply, compile, test,
                latency, 0
        );
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
    record EvalCase(String caseId, String sampleId, String description, List<ExpectedFinding> expectedFindings) {}

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
                    result.add(new EvalCase(caseId, sampleId, desc, expected));
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
