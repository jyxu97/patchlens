package com.patchlens.service;

import com.patchlens.model.*;
import com.patchlens.model.PatchValidation.Stage;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.repository.PatchValidationRepository;
import com.patchlens.sandbox.DirectorySnapshot;
import com.patchlens.sandbox.SandboxExecutor;
import com.patchlens.sandbox.SandboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Orchestration tests for generate → policy → sandbox → bounded repair.
 *
 * <p>The sandbox here is a deterministic in-memory {@link SandboxExecutor} that decides
 * compile/test outcomes by <em>inspecting the patched workspace</em>, so these tests prove the
 * patch was really applied to a fresh snapshot. Real Docker execution is covered separately by
 * {@code DockerSandboxIntegrationTest}.
 */
class PatchPipelineServiceTest {

    @TempDir Path tmp;

    private static final String FILE = "src/main/java/demo/Calc.java";
    private static final String PRISTINE = "package demo;\npublic class Calc {\n  int div(int a, int b) { return a / b; }\n}\n";

    private PatchValidationRepository validationRepo;
    private PatchSuggestionRepository suggestionRepo;
    private PatchGenerationService generation;
    private FakeSandbox sandbox;
    private PatchPipelineService pipeline;
    private DirectorySnapshot snapshot;
    private ReviewFinding finding;
    private final Deque<String> repairs = new ArrayDeque<>();

    @BeforeEach
    void setUp() throws Exception {
        Path fixture = tmp.resolve("fixture");
        Files.createDirectories(fixture.resolve("src/main/java/demo"));
        Files.writeString(fixture.resolve("pom.xml"), "<project/>");
        Files.writeString(fixture.resolve(FILE), PRISTINE);
        snapshot = new DirectorySnapshot("rev-1", "test/repo", List.of(fixture));

        validationRepo = mock(PatchValidationRepository.class);
        suggestionRepo = mock(PatchSuggestionRepository.class);
        when(validationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(suggestionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        sandbox = new FakeSandbox();
        PatchValidationService validation = new PatchValidationService(
                validationRepo, suggestionRepo, sandbox, SandboxProperties.forTests(tmp.resolve("sbx"), true));

        generation = mock(PatchGenerationService.class);
        when(generation.repair(any(), any(), anyString())).thenAnswer(inv -> {
            PatchSuggestion p = inv.getArgument(1);
            if (p.getRepairAttempts() >= PatchGenerationService.MAX_REPAIR_ATTEMPTS) throw new IllegalStateException("budget");
            p.setPatchText(repairs.pop());
            p.setRepairAttempts(p.getRepairAttempts() + 1);
            p.setStatus(ValidationStatus.PENDING);
            return p;
        });
        pipeline = new PatchPipelineService(generation, validation);

        finding = new ReviewFinding(UUID.randomUUID(), FILE, 3, 3, FindingCategory.CORRECTNESS,
                FindingSeverity.HIGH, "Division by zero", "b may be zero", 0.9, null);
    }

    private static String patchReplacing(String newBody) {
        return "--- a/" + FILE + "\n+++ b/" + FILE + "\n@@ -1,4 +1,4 @@\n package demo;\n public class Calc {\n"
                + "-  int div(int a, int b) { return a / b; }\n+" + newBody + "\n }\n";
    }

    private static final String GOOD   = patchReplacing("  int div(int a, int b) { return b == 0 ? 0 : a / b; } // FIXED");
    private static final String BROKEN = patchReplacing("  int div(int a, int b) { return b == 0 ? 0 : a / b } // BROKEN");
    private static final String NO_FIX = patchReplacing("  int div(int a, int b) { return a / b; } // still wrong");

    private PatchSuggestion candidate(String text) {
        return new PatchSuggestion(finding.getId(), text, "rationale");
    }

    @Test
    void initialPatchPassesValidation() {
        var result = pipeline.validateAndRepair(finding, candidate(GOOD), snapshot);

        assertThat(result.finalStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(result.attempts()).hasSize(1);
        PatchValidation pv = result.attempts().get(0);
        assertThat(pv.isPatchApplied()).isTrue();
        assertThat(pv.isCompileRan()).isTrue();
        assertThat(pv.isTestsRan()).isTrue();
        assertThat(pv.getRevision()).isEqualTo("rev-1");
        verify(generation, never()).repair(any(), any(), anyString());
    }

    @Test
    void compileFailureThenRepairSucceeds() {
        repairs.add(GOOD);
        var result = pipeline.validateAndRepair(finding, candidate(BROKEN), snapshot);

        assertThat(result.initialStatus()).isEqualTo(ValidationStatus.REJECTED_COMPILE);
        assertThat(result.finalStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(result.patch().getRepairAttempts()).isEqualTo(1);
        assertThat(result.patch().getStatus()).isEqualTo(ValidationStatus.VALIDATED);
        // repaired patch is validated against a fresh copy: it applies to the pristine file
        assertThat(result.attempts().get(1).isPatchApplied()).isTrue();
        verify(generation).repair(eq(finding), any(), contains("COMPILE"));
    }

    @Test
    void testFailureThenRepairSucceeds() {
        repairs.add(GOOD);
        var result = pipeline.validateAndRepair(finding, candidate(NO_FIX), snapshot);

        assertThat(result.initialStatus()).isEqualTo(ValidationStatus.REJECTED_TEST);
        assertThat(result.attempts().get(0).isCompilePassed()).isTrue();
        assertThat(result.finalStatus()).isEqualTo(ValidationStatus.VALIDATED);
        verify(generation).repair(eq(finding), any(), contains("expected FIXED"));
    }

    @Test
    void allRepairAttemptsFail_budgetIsEnforcedAndPatchStaysFailed() {
        repairs.add(BROKEN);
        repairs.add(NO_FIX);
        repairs.add(GOOD); // must never be requested
        var result = pipeline.validateAndRepair(finding, candidate(BROKEN), snapshot);

        assertThat(result.attempts()).hasSize(1 + PatchGenerationService.MAX_REPAIR_ATTEMPTS);
        assertThat(result.attempts()).extracting(PatchValidation::getAttemptNumber).containsExactly(0, 1, 2);
        assertThat(result.finalStatus()).isEqualTo(ValidationStatus.REJECTED_TEST);
        assertThat(result.patch().getStatus()).isNotEqualTo(ValidationStatus.VALIDATED);
        assertThat(result.patch().getRepairAttempts()).isEqualTo(2);
        verify(generation, times(2)).repair(any(), any(), anyString());
        assertThat(repairs).containsExactly(GOOD);
    }

    @Test
    void invalidUnifiedDiffIsRejectedByPolicyBeforeSandbox() {
        repairs.add(GOOD);
        var result = pipeline.validateAndRepair(finding, candidate("Just add a zero check."), snapshot);

        PatchValidation first = result.attempts().get(0);
        assertThat(first.getStatus()).isEqualTo(ValidationStatus.REJECTED_POLICY);
        assertThat(first.getFailureStage()).isEqualTo(Stage.POLICY);
        assertThat(first.isPatchApplied()).isFalse();
        // only the repaired attempt reached the sandbox
        assertThat(sandbox.stepsRun).containsExactly("compile", "test");
        assertThat(result.finalStatus()).isEqualTo(ValidationStatus.VALIDATED); // policy failures are repairable
    }

    @Test
    void disallowedFileModificationIsRejected() {
        String other = "--- a/src/main/java/demo/Other.java\n+++ b/src/main/java/demo/Other.java\n@@ -1 +1 @@\n-a\n+b\n";
        repairs.add(other);
        repairs.add(other);
        var result = pipeline.validateAndRepair(finding, candidate(other), snapshot);

        assertThat(result.attempts()).allSatisfy(pv -> {
            assertThat(pv.getStatus()).isEqualTo(ValidationStatus.REJECTED_POLICY);
            assertThat(pv.getFailureReason()).contains("outside the allowed set");
        });
        assertThat(sandbox.stepsRun).isEmpty();
    }

    @Test
    void validationErrorsArePersistedAccurately() {
        repairs.add(NO_FIX);
        repairs.add(NO_FIX);
        pipeline.validateAndRepair(finding, candidate(BROKEN), snapshot);

        var captor = org.mockito.ArgumentCaptor.forClass(PatchValidation.class);
        verify(validationRepo, times(3)).save(captor.capture());
        PatchValidation compileFail = captor.getAllValues().get(0);
        assertThat(compileFail.getStatus()).isEqualTo(ValidationStatus.REJECTED_COMPILE);
        assertThat(compileFail.getFailureStage()).isEqualTo(Stage.COMPILE);
        assertThat(compileFail.getFailureReason()).contains("Calc.java:[3,62] ';' expected");
        assertThat(compileFail.getLogs()).contains("=== compile");
        assertThat(compileFail.getPatchText()).isEqualTo(BROKEN);
        assertThat(compileFail.isTestsRan()).isFalse();

        PatchValidation testFail = captor.getAllValues().get(1);
        assertThat(testFail.getStatus()).isEqualTo(ValidationStatus.REJECTED_TEST);
        assertThat(testFail.getFailureStage()).isEqualTo(Stage.TEST);
        assertThat(testFail.isCompilePassed()).isTrue();
    }

    @Test
    void sandboxUnavailable_neverReportsValidatedAndDoesNotRepair() {
        sandbox.available = false;
        var result = pipeline.validateAndRepair(finding, candidate(GOOD), snapshot);

        assertThat(result.finalStatus()).isEqualTo(ValidationStatus.NOT_VALIDATED);
        assertThat(result.patch().getStatus()).isEqualTo(ValidationStatus.NOT_VALIDATED);
        assertThat(result.attempts().get(0).isCompileRan()).isFalse();
        verify(generation, never()).repair(any(), any(), anyString());
    }

    @Test
    void snapshotFailureIsAnInfrastructureErrorNotARepairablePatchFailure() {
        DirectorySnapshot missing = new DirectorySnapshot("rev-x", "test/repo", List.of(tmp.resolve("nope")));
        var result = pipeline.validateAndRepair(finding, candidate(GOOD), missing);

        assertThat(result.finalStatus()).isEqualTo(ValidationStatus.VALIDATION_ERROR);
        assertThat(result.attempts().get(0).getFailureStage()).isEqualTo(Stage.SNAPSHOT);
        verify(generation, never()).repair(any(), any(), anyString());
    }

    /** Decides outcomes from the patched file content, like a compiler and a test suite would. */
    static class FakeSandbox implements SandboxExecutor {
        boolean available = true;
        final List<String> stepsRun = new ArrayList<>();

        @Override public Availability availability() {
            return available ? Availability.ok() : Availability.no("docker not installed");
        }
        @Override public StepResult prefetchDependencies(Path dir, Path cache, int t) {
            return new StepResult("prefetch", 0, "ok", false, 1);
        }
        @Override public StepResult runStep(String step, String command, Path ws, Path scratch, Path cache, int t) {
            stepsRun.add(step);
            String src;
            try { src = Files.readString(ws.resolve(FILE)); } catch (Exception e) { return new StepResult(step, 1, e.toString(), false, 1); }
            if (step.equals("compile")) {
                return src.contains("BROKEN")
                        ? new StepResult(step, 1, "[ERROR] Calc.java:[3,62] ';' expected", false, 5)
                        : new StepResult(step, 0, "BUILD SUCCESS", false, 5);
            }
            return src.contains("FIXED")
                    ? new StepResult(step, 0, "Tests run: 1, Failures: 0", false, 5)
                    : new StepResult(step, 1, "CalcTest.divByZero: expected FIXED behaviour, got ArithmeticException", false, 5);
        }
    }
}
