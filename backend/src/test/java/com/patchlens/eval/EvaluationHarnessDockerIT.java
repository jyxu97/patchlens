package com.patchlens.eval;

import com.patchlens.model.*;
import com.patchlens.repository.EvaluationCaseResultRepository;
import com.patchlens.repository.EvaluationRunRepository;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.repository.PatchValidationRepository;
import com.patchlens.sandbox.DockerSandboxExecutor;
import com.patchlens.sandbox.SandboxProperties;
import com.patchlens.service.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end check of the evaluation harness and its metric definitions, with real Docker
 * validation of every patch.
 *
 * <p>The model is replaced by a <b>scripted</b> detector and patch source with known behaviour
 * so the expected metrics can be computed by hand. This verifies the harness, the denominators
 * and the initial-vs-final bookkeeping — it says nothing about model quality, and its numbers
 * are not evaluation results.
 * <ul>
 *   <li>detector: returns every labeled finding (HIGH) plus one hallucinated finding per case</li>
 *   <li>auth-001: initial candidate = reference fix → passes initially</li>
 *   <li>cache-001: initial candidate is a no-op; repair returns the reference fix → passes after repair</li>
 *   <li>all others: no-op candidates and no-op repairs → fail after 2 repairs</li>
 * </ul>
 */
@Tag("docker")
class EvaluationHarnessDockerIT {

    @TempDir Path tmp;

    @Test
    void harnessComputesMetricsFromRealValidationOutcomes() throws Exception {
        SandboxProperties props = SandboxProperties.forTests(Path.of("target", "sandbox-it").toAbsolutePath(), true);
        DockerSandboxExecutor executor = new DockerSandboxExecutor(props);
        assumeTrue(executor.availability().available(), "Docker unavailable — EvaluationHarnessDockerIT NOT executed");

        ObjectMapper mapper = new ObjectMapper();
        EvalDataset ds = EvalDataset.load("dataset-v2", tmp.resolve("ds"), mapper);
        Map<String, EvalDataset.Case> byTitle = new HashMap<>();
        ds.cases().forEach(c -> byTitle.put(c.metadata().title(), c));

        PatchValidationRepository vr = mock(PatchValidationRepository.class);
        PatchSuggestionRepository sr = mock(PatchSuggestionRepository.class);
        when(vr.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sr.save(any())).thenAnswer(i -> i.getArgument(0));
        PatchValidationService validation = new PatchValidationService(vr, sr, executor, props);

        IssueDetectionService detector = mock(IssueDetectionService.class);
        when(detector.detect(any(), any(), any(), any())).thenAnswer(inv -> {
            EvalDataset.Case c = byTitle.get(((PullRequestMetadata) inv.getArgument(1)).title());
            List<ReviewFinding> out = new ArrayList<>();
            for (EvalDataset.ExpectedFinding e : c.expectedFindings()) {
                out.add(withId(new ReviewFinding(UUID.randomUUID(), e.file(), e.lineStart(), e.lineEnd(), e.category(),
                        FindingSeverity.HIGH, e.id(), e.description(), 0.9, null)));
            }
            out.add(withId(new ReviewFinding(UUID.randomUUID(), "src/main/java/Hallucinated.java", 1, 1,
                    FindingCategory.SECURITY, FindingSeverity.MEDIUM, "hallucinated", "x", 0.9, null)));
            return out;
        });

        PatchGenerationService generator = mock(PatchGenerationService.class);
        Map<UUID, String> caseOfFinding = new HashMap<>();
        when(generator.generateCandidate(any(), any(), any())).thenAnswer(inv -> {
            ReviewFinding f = inv.getArgument(0);
            EvalDataset.Case c = caseFor(ds, f.getTitle());
            caseOfFinding.put(f.getId(), c.caseId());
            String text = c.caseId().equals("auth-001") ? c.referencePatch() : noOp(c, f.getFilePath());
            return Optional.of(new PatchSuggestion(f.getId(), text, "scripted"));
        });
        when(generator.repair(any(), any(), anyString())).thenAnswer(inv -> {
            ReviewFinding f = inv.getArgument(0);
            PatchSuggestion p = inv.getArgument(1);
            EvalDataset.Case c = caseFor(ds, f.getTitle());
            p.setPatchText(c.caseId().equals("cache-001") ? c.referencePatch() : noOp(c, f.getFilePath()));
            p.setRepairAttempts(p.getRepairAttempts() + 1);
            return p;
        });

        EvaluationRunRepository runRepo = mock(EvaluationRunRepository.class);
        EvaluationCaseResultRepository caseRepo = mock(EvaluationCaseResultRepository.class);
        when(runRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        EvaluationService service = new EvaluationService(new RiskScoringService(), detector, generator,
                new PatchPipelineService(generator, validation), validation, runRepo, caseRepo, mapper, props,
                "dataset-v2", "scripted", "scripted-model", 0.1, 4000, "v1");

        EvaluationRun run = service.execute(new EvaluationRun("3.0", "scripted", "v1", "dataset-v2"));

        assertThat(run.getStatus()).as(run.getErrorMessage()).isEqualTo("COMPLETED");
        assertThat(run.getDatasetVersion()).isEqualTo("v2");
        assertThat(run.getDatasetFingerprint()).isEqualTo(ds.fingerprint());
        assertThat(run.getSandboxAvailable()).isTrue();
        // detection: 8 labels all found once; 5 hallucinations are false positives
        assertThat(run.getTruePositives()).isEqualTo(8);
        assertThat(run.getFalsePositives()).isEqualTo(5);
        assertThat(run.getRecallScore()).isEqualTo(1.0);
        // patches: 5 targets; auth passes initially; cache passes after repair; 3 never pass
        assertThat(run.getPatchTargetCount()).isEqualTo(5);
        assertThat(run.getInitialPatchPassCount()).isEqualTo(1);
        assertThat(run.getFinalPatchPassCount()).isEqualTo(2);
        assertThat(run.getInitialPatchPassRate()).isEqualTo(0.2);
        assertThat(run.getFinalPatchPassRate()).isEqualTo(0.4);
        assertThat(run.getPatchPassImprovementPp()).isEqualTo(20.0);

        ArgumentCaptor<EvaluationCaseResult> cases = ArgumentCaptor.forClass(EvaluationCaseResult.class);
        verify(caseRepo, times(5)).save(cases.capture());
        EvaluationCaseResult cache = cases.getAllValues().stream().filter(r -> r.getCaseId().equals("cache-001")).findFirst().orElseThrow();
        assertThat(cache.getRepairCount()).isEqualTo(1);
        assertThat(cache.getPatchResultsJson()).contains("\"initialStatus\":\"REJECTED_TEST\"").contains("\"finalStatus\":\"VALIDATED\"");
        EvaluationCaseResult pagination = cases.getAllValues().stream().filter(r -> r.getCaseId().equals("pagination-001")).findFirst().orElseThrow();
        assertThat(pagination.getRepairCount()).isEqualTo(PatchGenerationService.MAX_REPAIR_ATTEMPTS);
    }

    private static ReviewFinding withId(ReviewFinding f) {
        try {
            var id = ReviewFinding.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(f, UUID.randomUUID());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return f;
    }

    private static EvalDataset.Case caseFor(EvalDataset ds, String expectedId) {
        return ds.cases().stream()
                .filter(c -> c.expectedFindings().stream().anyMatch(e -> e.id().equals(expectedId)))
                .findFirst().orElseThrow();
    }

    private static String noOp(EvalDataset.Case c, String file) {
        try {
            String first = java.nio.file.Files.readAllLines(c.headDir().resolve(file)).get(0);
            return "--- a/" + file + "\n+++ b/" + file + "\n@@ -1,1 +1,2 @@\n " + first + "\n+// no behaviour change\n";
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
