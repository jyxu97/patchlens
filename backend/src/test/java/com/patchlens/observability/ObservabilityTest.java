package com.patchlens.observability;

import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.PatchValidation;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.repository.PatchValidationRepository;
import com.patchlens.sandbox.DirectorySnapshot;
import com.patchlens.sandbox.SandboxExecutor;
import com.patchlens.sandbox.SandboxProperties;
import com.patchlens.service.PatchValidationService;
import com.patchlens.util.Redactor;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Metrics, bounded sandbox concurrency, and secret redaction in persisted output. */
class ObservabilityTest {

    @TempDir Path tmp;
    private SimpleMeterRegistry registry;
    private static final String FILE = "src/main/java/A.java";
    private static final String PATCH = "--- a/" + FILE + "\n+++ b/" + FILE + "\n@@ -1 +1 @@\n-class A {}\n+class A { }\n";

    @BeforeEach
    void registry() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
    }

    private PatchValidationService service(SandboxExecutor sandbox, int maxConcurrent) throws Exception {
        PatchValidationRepository vr = mock(PatchValidationRepository.class);
        PatchSuggestionRepository sr = mock(PatchSuggestionRepository.class);
        when(vr.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sr.save(any())).thenAnswer(i -> i.getArgument(0));
        SandboxProperties base = SandboxProperties.forTests(tmp.resolve("sbx"), true);
        SandboxProperties props = new SandboxProperties(true, "docker", base.getImage(),
                base.getWorkRoot().toString(), base.getCacheRoot().toString(), "1.0", "1g", 256, "65534:65534", "64m",
                maxConcurrent, 900, 600, 10_000);
        return new PatchValidationService(vr, sr, sandbox, props);
    }

    private DirectorySnapshot fixture() throws Exception {
        Path f = tmp.resolve("fixture-" + UUID.randomUUID());
        Files.createDirectories(f.resolve("src/main/java"));
        Files.writeString(f.resolve("pom.xml"), "<project/>");
        Files.writeString(f.resolve(FILE), "class A {}\n");
        return new DirectorySnapshot("rev", "repo/x", List.of(f));
    }

    @Test
    void concurrentSandboxValidationsAreBounded() throws Exception {
        AtomicInteger running = new AtomicInteger(), maxSeen = new AtomicInteger();
        SandboxExecutor slow = new SandboxExecutor() {
            public Availability availability() { return Availability.ok(); }
            public StepResult prefetchDependencies(Path d, Path c, int t) { return new StepResult("prefetch", 0, "", false, 1); }
            public StepResult runStep(String step, String cmd, Path ws, Path s, Path c, int t) {
                int now = running.incrementAndGet();
                maxSeen.accumulateAndGet(now, Math::max);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                running.decrementAndGet();
                return new StepResult(step, 0, "ok", false, 100);
            }
        };
        PatchValidationService svc = service(slow, 2);
        DirectorySnapshot snap = fixture();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<PatchValidation>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> svc.validate(new PatchSuggestion(UUID.randomUUID(), PATCH, "r"), snap, Set.of(FILE), 0)));
        }
        for (var f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(maxSeen.get()).isLessThanOrEqualTo(2);
        assertThat(registry.get(PatchLensMetrics.VALIDATION_OUTCOME).tag("status", "VALIDATED").counter().count()).isEqualTo(6.0);
    }

    @Test
    void credentialsInSandboxOutputAreRedactedBeforePersistence() throws Exception {
        String token = "ghp_" + "a".repeat(36);
        SandboxExecutor leaky = new SandboxExecutor() {
            public Availability availability() { return Availability.ok(); }
            public StepResult prefetchDependencies(Path d, Path c, int t) { return new StepResult("prefetch", 0, "", false, 1); }
            public StepResult runStep(String step, String cmd, Path ws, Path s, Path c, int t) {
                return new StepResult(step, 1, "[ERROR] auth failed with token " + token + " and key sk-" + "b".repeat(40), false, 1);
            }
        };
        PatchValidation pv = service(leaky, 1).validate(new PatchSuggestion(UUID.randomUUID(), PATCH, "r"), fixture(), Set.of(FILE), 0);

        assertThat(pv.getLogs()).doesNotContain(token).doesNotContain("sk-bbbb").contains("[REDACTED]");
        assertThat(pv.getFailureReason()).doesNotContain(token);
    }

    @Test
    void modelCallsRecordLatencyAndErrorsWithoutMessageText() {
        assertThat(PatchLensMetrics.timeModel("review", () -> "ok")).isEqualTo("ok");
        Throwable t = catchThrowable(() -> PatchLensMetrics.timeModel("review", () -> { throw new IllegalStateException("secret prompt text"); }));
        assertThat(t).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get(PatchLensMetrics.MODEL_LATENCY).tag("operation", "review").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get(PatchLensMetrics.MODEL_ERRORS).tag("exception", "IllegalStateException").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags().toString()).doesNotContain("secret prompt"));
    }

    @Test
    void redactorCoversCommonCredentialShapes() {
        assertThat(Redactor.redact("Authorization: Bearer abcdefghijklmnop1234")).doesNotContain("abcdefghijklmnop1234");
        assertThat(Redactor.redact("github_pat_" + "x".repeat(30))).isEqualTo("[REDACTED]");
        assertThat(Redactor.redact("password=hunter22")).doesNotContain("hunter22");
        assertThat(Redactor.redact("Compilation failed at Calc.java:12")).isEqualTo("Compilation failed at Calc.java:12");
    }
}
