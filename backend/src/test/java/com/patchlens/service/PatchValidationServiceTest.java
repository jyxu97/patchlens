package com.patchlens.service;

import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.PatchValidation;
import com.patchlens.model.PatchValidation.Stage;
import com.patchlens.model.ValidationStatus;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Classification of infrastructure failures vs. patch failures (no Docker required). */
class PatchValidationServiceTest {

    @TempDir Path tmp;
    private static final String FILE = "src/main/java/A.java";
    private static final String PATCH = "--- a/" + FILE + "\n+++ b/" + FILE + "\n@@ -1 +1 @@\n-class A {}\n+class A { }\n";

    private Path fixture;
    private SandboxProperties props;
    private BiFunction<String, Integer, SandboxExecutor.StepResult> stepBehaviour;
    private SandboxExecutor.StepResult prefetchResult;
    private int prefetchCalls;

    @BeforeEach
    void setUp() throws Exception {
        fixture = tmp.resolve("fixture");
        Files.createDirectories(fixture.resolve("src/main/java"));
        Files.writeString(fixture.resolve("pom.xml"), "<project/>");
        Files.writeString(fixture.resolve(FILE), "class A {}\n");
        props = SandboxProperties.forTests(tmp.resolve("sbx"), true);
        stepBehaviour = (step, timeout) -> new SandboxExecutor.StepResult(step, 0, "ok", false, 1);
        prefetchResult = new SandboxExecutor.StepResult("prefetch", 0, "ok", false, 1);
    }

    private PatchValidation validate() {
        PatchValidationRepository vr = mock(PatchValidationRepository.class);
        PatchSuggestionRepository sr = mock(PatchSuggestionRepository.class);
        when(vr.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sr.save(any())).thenAnswer(i -> i.getArgument(0));
        SandboxExecutor sandbox = new SandboxExecutor() {
            public Availability availability() { return Availability.ok(); }
            public StepResult prefetchDependencies(Path d, Path c, int t) { prefetchCalls++; return prefetchResult; }
            public StepResult runStep(String step, String cmd, Path ws, Path s, Path c, int t) { return stepBehaviour.apply(step, t); }
        };
        PatchValidationService svc = new PatchValidationService(vr, sr, sandbox, props);
        return svc.validate(new PatchSuggestion(UUID.randomUUID(), PATCH, "r"),
                new DirectorySnapshot("rev", "repo/x", List.of(fixture)), Set.of(FILE), 0);
    }

    @Test
    void missingBuildFileIsAValidationError() throws Exception {
        Files.delete(fixture.resolve("pom.xml"));
        PatchValidation pv = validate();
        assertThat(pv.getStatus()).isEqualTo(ValidationStatus.VALIDATION_ERROR);
        assertThat(pv.getFailureStage()).isEqualTo(Stage.CONFIG);
        assertThat(pv.getFailureReason()).contains("pom.xml").contains("not found");
    }

    @Test
    void dependencyPrefetchFailureIsAValidationErrorNotACompileFailure() {
        prefetchResult = new SandboxExecutor.StepResult("prefetch", 1, "Could not resolve dependencies for project", false, 1);
        PatchValidation pv = validate();
        assertThat(pv.getStatus()).isEqualTo(ValidationStatus.VALIDATION_ERROR);
        assertThat(pv.getFailureStage()).isEqualTo(Stage.DEPENDENCIES);
        assertThat(pv.isCompileRan()).isFalse();
    }

    @Test
    void dependencyMissingOfflineDuringCompileIsAValidationError() {
        stepBehaviour = (step, t) -> new SandboxExecutor.StepResult(step, 1,
                "[ERROR] Cannot access central (https://repo.maven.apache.org/maven2) in offline mode", false, 1);
        PatchValidation pv = validate();
        assertThat(pv.getStatus()).isEqualTo(ValidationStatus.VALIDATION_ERROR);
        assertThat(pv.getFailureStage()).isEqualTo(Stage.DEPENDENCIES);
    }

    @Test
    void testTimeoutIsRecordedAsTimedOutTestFailure() {
        stepBehaviour = (step, t) -> step.equals("test")
                ? new SandboxExecutor.StepResult(step, -1, "[TIMEOUT after 300s]", true, 300_000)
                : new SandboxExecutor.StepResult(step, 0, "ok", false, 1);
        PatchValidation pv = validate();
        assertThat(pv.getStatus()).isEqualTo(ValidationStatus.REJECTED_TEST);
        assertThat(pv.isTimedOut()).isTrue();
        assertThat(pv.getFailureReason()).contains("timed out");
    }

    @Test
    void repositoryConfigTimeoutsArePassedToTheSandboxAndCapped() throws Exception {
        Files.writeString(fixture.resolve("patchlens.yaml"), "timeouts:\n  compileSeconds: 5\n  testSeconds: 100000\n");
        List<Integer> seen = new java.util.ArrayList<>();
        stepBehaviour = (step, t) -> { seen.add(t); return new SandboxExecutor.StepResult(step, 0, "ok", false, 1); };
        PatchValidation pv = validate();
        assertThat(pv.getStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(pv.getConfigSource()).isEqualTo("patchlens.yaml");
        assertThat(seen).containsExactly(5, props.getMaxStepSeconds());
    }

    @Test
    void dependenciesArePrefetchedOncePerDescriptorSet_andWorkspacesAreDeleted() throws Exception {
        validate();
        validate();
        assertThat(prefetchCalls).isEqualTo(1);
        try (Stream<Path> s = Files.list(props.getWorkRoot())) {
            assertThat(s.toList()).isEmpty();
        }
    }
}
