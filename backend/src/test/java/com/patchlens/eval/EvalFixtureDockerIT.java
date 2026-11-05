package com.patchlens.eval;

import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.PatchValidation;
import com.patchlens.model.ValidationStatus;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.repository.PatchValidationRepository;
import com.patchlens.sandbox.DirectorySnapshot;
import com.patchlens.sandbox.DockerSandboxExecutor;
import com.patchlens.sandbox.SandboxProperties;
import com.patchlens.service.PatchValidationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves every dataset fixture is meaningful, with real Docker builds:
 * <ul>
 *   <li>the PR head compiles and its own tests pass;</li>
 *   <li>with the hidden oracle tests, the unfixed code <b>fails</b>;</li>
 *   <li>the case's reference fix makes the oracle <b>pass</b>.</li>
 * </ul>
 * Skipped (not faked) when Docker is unavailable. Run: {@code ./mvnw test -Dtest=EvalFixtureDockerIT}
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalFixtureDockerIT {

    @TempDir static Path tmp;
    private PatchValidationService service;
    private EvalDataset dataset;

    @BeforeAll
    void setUp() throws Exception {
        SandboxProperties props = SandboxProperties.forTests(Path.of("target", "sandbox-it").toAbsolutePath(), true);
        DockerSandboxExecutor executor = new DockerSandboxExecutor(props);
        var a = executor.availability();
        assumeTrue(a.available(), "Docker unavailable — EvalFixtureDockerIT NOT executed: " + a.reason());
        PatchValidationRepository vr = mock(PatchValidationRepository.class);
        PatchSuggestionRepository sr = mock(PatchSuggestionRepository.class);
        when(vr.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sr.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new PatchValidationService(vr, sr, executor, props);
        dataset = EvalDataset.load("dataset-v2", tmp.resolve("ds"), new ObjectMapper());
    }

    Stream<EvalDataset.Case> cases() { return dataset.cases().stream(); }

    /** A comment-only patch: applies cleanly but changes no behaviour. */
    private static String noOpPatch(Path head, String file) throws Exception {
        String first = Files.readAllLines(head.resolve(file)).get(0);
        return "--- a/" + file + "\n+++ b/" + file + "\n@@ -1,1 +1,2 @@\n " + first + "\n+// no behaviour change\n";
    }

    private PatchValidation validate(String patch, String file, DirectorySnapshot snap) {
        return service.validate(new PatchSuggestion(UUID.randomUUID(), patch, "fixture-check"), snap, Set.of(file), 0);
    }

    @TestFactory
    Stream<DynamicTest> everyFixtureFailsBeforeTheFixAndPassesAfter() {
        return cases().map(c -> DynamicTest.dynamicTest(c.caseId(), () -> {
            String file = c.patchTargets().get(0).file();

            PatchValidation headOnly = validate(noOpPatch(c.headDir(), file), file,
                    new DirectorySnapshot(c.caseId() + "-head", "eval-v2", List.of(c.headDir())));
            assertThat(headOnly.getStatus()).as("%s: PR head builds and its own tests pass\n%s", c.caseId(), headOnly.getLogs())
                    .isEqualTo(ValidationStatus.VALIDATED);

            PatchValidation buggy = validate(noOpPatch(c.headDir(), file), file,
                    (DirectorySnapshot) c.validationSnapshot("v2"));
            assertThat(buggy.getStatus()).as("%s: oracle must fail on the labeled bug\n%s", c.caseId(), buggy.getLogs())
                    .isEqualTo(ValidationStatus.REJECTED_TEST);

            PatchValidation fixed = validate(c.referencePatch(), file, (DirectorySnapshot) c.validationSnapshot("v2"));
            assertThat(fixed.getStatus()).as("%s: reference fix must pass the oracle\n%s", c.caseId(), fixed.getLogs())
                    .isEqualTo(ValidationStatus.VALIDATED);
        }));
    }
}
