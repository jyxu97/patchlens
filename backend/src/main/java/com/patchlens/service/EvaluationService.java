package com.patchlens.service;

import com.patchlens.eval.EvalDataset;
import com.patchlens.eval.EvalMatcher;
import com.patchlens.model.*;
import com.patchlens.repository.EvaluationCaseResultRepository;
import com.patchlens.repository.EvaluationRunRepository;
import com.patchlens.sandbox.PatchPolicy;
import com.patchlens.sandbox.SandboxProperties;
import com.patchlens.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Offline evaluation harness over a versioned, executable dataset (default {@code dataset-v2}).
 *
 * <h2>Issue detection</h2>
 * Each case's PR is run through the production {@link IssueDetectionService} (diff understanding,
 * retrieval, model detection, deterministic filter/grounding). Detected findings are matched to
 * labeled expected findings one-to-one by {@link EvalMatcher}; precision = TP/(TP+FP) and
 * recall = TP/(TP+FN), micro-averaged over all cases.
 *
 * <h2>Patch validation</h2>
 * The denominator of both pass rates is the number of labeled <em>patch targets</em> in the
 * dataset — fixed, independent of what the model detects. For each target:
 * <ul>
 *   <li>if it was matched by a HIGH/MEDIUM detection, the production {@link PatchPipelineService}
 *       generates a patch and validates it in the Docker sandbox against the case's head tree plus
 *       hidden oracle tests, with bounded repair;</li>
 *   <li>otherwise it counts as a failure for both rates (recorded with the reason).</li>
 * </ul>
 * initial rate = targets whose first candidate was VALIDATED / targets; final rate = targets whose
 * final candidate was VALIDATED / targets; improvement = (final − initial) × 100 percentage points.
 * If the sandbox is unavailable, patch rates are recorded as null — never as measured values.
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private static final String PIPELINE_VERSION = "3.0";

    private final RiskScoringService riskScoringService;
    private final IssueDetectionService issueDetectionService;
    private final PatchGenerationService patchGenerationService;
    private final PatchPipelineService patchPipelineService;
    private final PatchValidationService patchValidationService;
    private final EvaluationRunRepository runRepository;
    private final EvaluationCaseResultRepository caseResultRepository;
    private final ObjectMapper objectMapper;
    private final SandboxProperties sandboxProperties;
    private final String datasetDir;
    private final String aiMode;
    private final String modelName;
    private final double temperature;
    private final int maxTokens;
    private final String promptVersion;

    public EvaluationService(RiskScoringService riskScoringService,
                             IssueDetectionService issueDetectionService,
                             PatchGenerationService patchGenerationService,
                             PatchPipelineService patchPipelineService,
                             PatchValidationService patchValidationService,
                             EvaluationRunRepository runRepository,
                             EvaluationCaseResultRepository caseResultRepository,
                             ObjectMapper objectMapper,
                             SandboxProperties sandboxProperties,
                             @Value("${patchlens.eval.dataset:dataset-v2}") String datasetDir,
                             @Value("${ai.mode:mock}") String aiMode,
                             @Value("${openai.model:gpt-4o-mini}") String modelName,
                             @Value("${openai.temperature:0.1}") double temperature,
                             @Value("${openai.max-tokens:4000}") int maxTokens,
                             @Value("${prompt.version:v1}") String promptVersion) {
        this.riskScoringService = riskScoringService;
        this.issueDetectionService = issueDetectionService;
        this.patchGenerationService = patchGenerationService;
        this.patchPipelineService = patchPipelineService;
        this.patchValidationService = patchValidationService;
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
        this.objectMapper = objectMapper;
        this.sandboxProperties = sandboxProperties;
        this.datasetDir = datasetDir;
        this.aiMode = aiMode;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.promptVersion = promptVersion;
    }

    @Async
    public void runAsync(UUID runId) {
        EvaluationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("EvaluationRun not found: " + runId));
        execute(run);
    }

    /** Creates a run record whose model/prompt fields reflect the actual configuration. */
    public EvaluationRun createRun() {
        String model = "mock".equalsIgnoreCase(aiMode) ? "mock" : modelName;
        EvaluationRun run = new EvaluationRun(PIPELINE_VERSION, model, promptVersion, datasetDir);
        run.setStatus("RUNNING");
        run.setAiMode(aiMode);
        return runRepository.save(run);
    }

    // =====================================================================
    // Core evaluation loop
    // =====================================================================

    /** Runs the whole dataset synchronously. Public for tests and offline drivers. */
    public EvaluationRun execute(EvaluationRun run) {
        Path work = null;
        try {
            work = Files.createTempDirectory("patchlens-eval-");
            EvalDataset dataset = EvalDataset.load(datasetDir, work.resolve("dataset"), objectMapper);
            boolean sandboxAvailable = patchValidationService.availability().available();

            run.setDatasetVersion(dataset.version());
            run.setDatasetFingerprint(dataset.fingerprint());
            run.setPromptFingerprint(promptFingerprint());
            run.setInferenceConfigJson(objectMapper.writeValueAsString(inferenceConfig()));
            run.setSandboxAvailable(sandboxAvailable);
            runRepository.save(run);

            Totals totals = new Totals();
            for (EvalDataset.Case c : dataset.cases()) {
                EvaluationCaseResult result = evaluateCase(run.getId(), dataset, c, totals);
                caseResultRepository.save(result);
            }
            applyMetrics(run, totals, sandboxAvailable);
            run.setStatus("COMPLETED");
        } catch (Exception e) {
            log.error("Eval run {} failed: {}", run.getId(), e.getMessage(), e);
            run.setStatus("FAILED");
            run.setErrorMessage(Redactor.redact(String.valueOf(e.getMessage())));
        } finally {
            run.setCompletedAt(Instant.now());
            runRepository.save(run);
            if (work != null) PatchValidationService.deleteRecursively(work);
        }
        log.info("Eval run {} [{} {}] dataset={} ({}) — precision={} recall={} patch pass {}→{} ({} pp) sandbox={}",
                run.getId(), run.getAiMode(), run.getModelName(), run.getDatasetVersion(), run.getDatasetFingerprint(),
                run.getPrecisionScore(), run.getRecallScore(),
                run.getInitialPatchPassRate(), run.getFinalPatchPassRate(), run.getPatchPassImprovementPp(),
                run.getSandboxAvailable());
        return run;
    }

    /** Aggregates across cases. */
    static final class Totals {
        int tp, fp, fn, expected, detected;
        int patchTargets, attempted, initialPass, finalPass;
        int finalApply, initialCompile, finalCompile, initialTest, finalTest;
    }

    /** Outcome for one labeled patch target. */
    record PatchTargetOutcome(String expectedId, boolean attempted, String skipReason,
                              String detectedTitle, ValidationStatus initialStatus, ValidationStatus finalStatus,
                              int repairCount, String initialPatch, String finalPatch, String finalFailureReason,
                              boolean initialApply, boolean initialCompile, boolean initialTest,
                              boolean finalApply, boolean finalCompile, boolean finalTest) {
        boolean initialPassed() { return initialStatus == ValidationStatus.VALIDATED; }
        boolean finalPassed()   { return finalStatus == ValidationStatus.VALIDATED; }

        static PatchTargetOutcome skipped(String expectedId, String reason, String detectedTitle) {
            return new PatchTargetOutcome(expectedId, false, reason, detectedTitle, null, null, 0,
                    null, null, null, false, false, false, false, false, false);
        }
    }

    private EvaluationCaseResult evaluateCase(UUID runId, EvalDataset dataset, EvalDataset.Case c, Totals totals) {
        long start = System.currentTimeMillis();
        List<EvalDataset.ExpectedFinding> expected = c.expectedFindings();
        totals.expected += expected.size();
        totals.patchTargets += c.patchTargets().size();

        List<ReviewFinding> detected = List.of();
        EvalMatcher.Result match;
        List<PatchTargetOutcome> patchOutcomes = new ArrayList<>();
        String error = null;
        try {
            List<RiskScore> riskScores = riskScoringService.score(c.changedFiles());
            detected = issueDetectionService.detect(UUID.randomUUID(), c.metadata(), c.changedFiles(), riskScores);

            List<EvalMatcher.Detected> det = new ArrayList<>();
            for (int i = 0; i < detected.size(); i++) {
                ReviewFinding f = detected.get(i);
                det.add(new EvalMatcher.Detected(i, f.getCategory(), f.getFilePath(),
                        f.getLineStart() == null ? 0 : f.getLineStart(),
                        f.getLineEnd() == null ? 0 : f.getLineEnd()));
            }
            match = EvalMatcher.match(expected.stream().map(EvalDataset.ExpectedFinding::toMatcher).toList(), det);

            for (EvalDataset.ExpectedFinding target : c.patchTargets()) {
                patchOutcomes.add(evaluatePatchTarget(dataset, c, target, match, detected));
            }
        } catch (Exception e) {
            log.error("Eval case {} failed: {}", c.caseId(), e.getMessage(), e);
            error = Redactor.redact(String.valueOf(e.getMessage()));
            // A crashed case still counts against recall and the patch denominator.
            match = new EvalMatcher.Result(List.of(), 0, 0, expected.size());
            patchOutcomes.clear();
            for (EvalDataset.ExpectedFinding target : c.patchTargets()) {
                patchOutcomes.add(PatchTargetOutcome.skipped(target.id(), "case error: " + error, null));
            }
        }

        totals.tp += match.truePositives();
        totals.fp += match.falsePositives();
        totals.fn += match.falseNegatives();
        totals.detected += detected.size();
        for (PatchTargetOutcome o : patchOutcomes) {
            if (o.attempted()) totals.attempted++;
            if (o.initialPassed()) totals.initialPass++;
            if (o.finalPassed()) totals.finalPass++;
            if (o.finalApply()) totals.finalApply++;
            if (o.initialCompile()) totals.initialCompile++;
            if (o.finalCompile()) totals.finalCompile++;
            if (o.initialTest()) totals.initialTest++;
            if (o.finalTest()) totals.finalTest++;
        }

        PatchTargetOutcome first = patchOutcomes.isEmpty() ? null : patchOutcomes.get(0);
        EvaluationCaseResult result = new EvaluationCaseResult(
                runId, c.caseId(), serializeFindings(detected),
                match.truePositives(), match.falsePositives(), match.falseNegatives(),
                first != null && first.initialApply(), first != null && first.initialCompile(), first != null && first.initialTest(),
                first != null && first.finalApply(), first != null && first.finalCompile(), first != null && first.finalTest(),
                first == null ? 0 : first.repairCount(), System.currentTimeMillis() - start, 0);
        result.setExpectedFindingsJson(json(expected));
        result.setMatchesJson(json(match.matches()));
        result.setPatchResultsJson(json(patchOutcomes));
        result.setErrorMessage(error);
        return result;
    }

    private PatchTargetOutcome evaluatePatchTarget(EvalDataset dataset, EvalDataset.Case c,
                                                   EvalDataset.ExpectedFinding target,
                                                   EvalMatcher.Result match, List<ReviewFinding> detected) {
        Optional<Integer> idx = match.detectedFor(target.id());
        if (idx.isEmpty()) return PatchTargetOutcome.skipped(target.id(), "not detected", null);

        ReviewFinding finding = detected.get(idx.get());
        if (!PatchGenerationService.isEligible(finding)) {
            return PatchTargetOutcome.skipped(target.id(),
                    "detected with " + finding.getSeverity() + " severity — no patch generated (production rule: HIGH/MEDIUM only)",
                    finding.getTitle());
        }
        ChangedFile file = c.changedFiles().stream()
                .filter(cf -> cf.filename().equals(finding.getFilePath())).findFirst().orElse(null);
        if (file == null) return PatchTargetOutcome.skipped(target.id(), "finding file not in PR", finding.getTitle());

        Optional<PatchSuggestion> candidate = patchGenerationService.generateCandidate(finding, file, List.of());
        if (candidate.isEmpty()) {
            return new PatchTargetOutcome(target.id(), true, "patch generation failed", finding.getTitle(),
                    null, null, 0, null, null, null, false, false, false, false, false, false);
        }

        PatchPipelineService.PatchResult pr = patchPipelineService.validateAndRepair(
                finding, candidate.get(), c.validationSnapshot(dataset.version()));
        PatchValidation init = pr.attempts().get(0);
        PatchValidation last = pr.finalAttempt();
        return new PatchTargetOutcome(target.id(), true, null, finding.getTitle(),
                init.getStatus(), last.getStatus(), pr.patch().getRepairAttempts(),
                init.getPatchText(), last.getPatchText(), last.getFailureReason(),
                init.isPatchApplied(), init.isCompilePassed(), init.isTestsPassed(),
                last.isPatchApplied(), last.isCompilePassed(), last.isTestsPassed());
    }

    static void applyMetrics(EvaluationRun run, Totals t, boolean sandboxAvailable) {
        run.setTruePositives(t.tp);
        run.setFalsePositives(t.fp);
        run.setFalseNegatives(t.fn);
        run.setExpectedCount(t.expected);
        run.setDetectedCount(t.detected);
        run.setPrecisionScore(t.tp + t.fp > 0 ? (double) t.tp / (t.tp + t.fp) : null);
        run.setRecallScore(t.tp + t.fn > 0 ? (double) t.tp / (t.tp + t.fn) : null);

        run.setPatchTargetCount(t.patchTargets);
        run.setPatchAttemptedCount(t.attempted);
        run.setInitialPatchPassCount(t.initialPass);
        run.setFinalPatchPassCount(t.finalPass);
        if (sandboxAvailable && t.patchTargets > 0) {
            double initial = (double) t.initialPass / t.patchTargets;
            double fin = (double) t.finalPass / t.patchTargets;
            run.setInitialPatchPassRate(initial);
            run.setFinalPatchPassRate(fin);
            run.setPatchPassImprovementPp(Math.round((fin - initial) * 1000.0) / 10.0);
            run.setPatchApplyRate((double) t.finalApply / t.patchTargets);
            run.setInitialCompileSuccessRate((double) t.initialCompile / t.patchTargets);
            run.setCompileSuccessRate((double) t.finalCompile / t.patchTargets);
            run.setInitialTestPassRate((double) t.initialTest / t.patchTargets);
            run.setTestPassRate((double) t.finalTest / t.patchTargets);
        }
    }

    // =====================================================================
    // Reproducibility metadata
    // =====================================================================

    private Map<String, Object> inferenceConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiMode", aiMode);
        m.put("model", "mock".equalsIgnoreCase(aiMode) ? "mock" : modelName);
        m.put("temperature", temperature);
        m.put("maxTokens", maxTokens);
        m.put("promptVersion", promptVersion);
        m.put("maxRepairAttempts", PatchGenerationService.MAX_REPAIR_ATTEMPTS);
        m.put("maxPatchLines", PatchPolicy.MAX_PATCH_LINES);
        m.put("patchEligibleSeverities", List.of("HIGH", "MEDIUM"));
        m.put("sandboxImage", sandboxProperties.getImage());
        m.put("matchRule", Map.of("sameFile", true, "sameCategory", true,
                "lineOverlapTolerance", EvalMatcher.LINE_TOLERANCE, "assignment", "one-to-one maximum matching"));
        m.put("patchRateDenominator", "all labeled patch targets in the dataset");
        return m;
    }

    private static String promptFingerprint() {
        try {
            Resource[] prompts = new PathMatchingResourcePatternResolver().getResources("classpath*:prompts/*.txt");
            Arrays.sort(prompts, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Resource r : prompts) {
                md.update(String.valueOf(r.getFilename()).getBytes(StandardCharsets.UTF_8));
                try (InputStream in = r.getInputStream()) { md.update(in.readAllBytes()); }
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String json(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private String serializeFindings(List<ReviewFinding> findings) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            ReviewFinding f = findings.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("file", f.getFilePath());
            m.put("lineStart", f.getLineStart());
            m.put("lineEnd", f.getLineEnd());
            m.put("category", f.getCategory().name());
            m.put("severity", f.getSeverity().name());
            m.put("title", f.getTitle());
            m.put("confidence", f.getConfidence());
            list.add(m);
        }
        String s = json(list);
        return s == null ? "[]" : s;
    }
}
