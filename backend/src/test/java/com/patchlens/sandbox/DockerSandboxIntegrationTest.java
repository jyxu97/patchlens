package com.patchlens.sandbox;

import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.PatchValidation;
import com.patchlens.model.ValidationStatus;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.repository.PatchValidationRepository;
import com.patchlens.service.PatchValidationService;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Real Docker execution against a compilable Maven fixture ({@code sandbox-fixtures/calc}).
 *
 * <p>Requires a reachable Docker daemon; the whole class is <b>skipped</b> (not faked) otherwise.
 * The first run pulls the sandbox image and resolves Maven dependencies into the cache, so it is
 * slow; later runs reuse the cache under {@code target/sandbox-it}.
 *
 * <p>Run with: {@code ./mvnw test -Dtest=DockerSandboxIntegrationTest}
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DockerSandboxIntegrationTest {

    private static final String FILE = "src/main/java/demo/Calc.java";
    private static final Set<String> ALLOWED = Set.of(FILE);

    private SandboxProperties props;
    private DockerSandboxExecutor executor;
    private PatchValidationService service;
    private RepositorySnapshot snapshot;

    @BeforeAll
    void setUp() throws Exception {
        Path root = Path.of("target", "sandbox-it").toAbsolutePath();
        props = SandboxProperties.forTests(root, true);
        executor = new DockerSandboxExecutor(props);
        SandboxExecutor.Availability a = executor.availability();
        assumeTrue(a.available(), "Docker unavailable — DockerSandboxIntegrationTest NOT executed: " + a.reason());

        PatchValidationRepository vr = mock(PatchValidationRepository.class);
        PatchSuggestionRepository sr = mock(PatchSuggestionRepository.class);
        when(vr.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sr.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new PatchValidationService(vr, sr, executor, props);
        snapshot = new DirectorySnapshot("calc-fixture", "it/calc", List.of(fixture("calc")));
    }

    static Path fixture(String name) throws URISyntaxException {
        return Path.of(DockerSandboxIntegrationTest.class.getResource("/sandbox-fixtures/" + name).toURI());
    }

    private static String replaceBody(String newLine) {
        return "--- a/" + FILE + "\n+++ b/" + FILE + "\n@@ -5,4 +5,4 @@\n"
                + "     /** Returns a / b, or 0 when b is 0. */\n"
                + "     public int safeDiv(int a, int b) {\n"
                + "-        return a / b;\n"
                + "+" + newLine + "\n"
                + "     }\n";
    }

    private PatchValidation validate(String patchText) {
        PatchSuggestion p = new PatchSuggestion(UUID.randomUUID(), patchText, "test");
        return service.validate(p, snapshot, ALLOWED, 0);
    }

    @Test @Order(1)
    void unpatchedBugFailsTests_andCorrectFixValidates() {
        // A no-op-ish patch (comment only) leaves the bug: the fixture's own test must fail…
        PatchValidation bug = validate(replaceBody("        return a / b; // unchanged behaviour"));
        assertThat(bug.getStatus()).as(bug.getLogs()).isEqualTo(ValidationStatus.REJECTED_TEST);
        assertThat(bug.isCompilePassed()).isTrue();
        assertThat(bug.getFailureReason()).contains("divisionByZeroReturnsZero");

        // …and a correct fix must pass compile and tests for real.
        PatchValidation fixed = validate(replaceBody("        return b == 0 ? 0 : a / b;"));
        assertThat(fixed.getStatus()).as(fixed.getLogs()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(fixed.isPatchApplied()).isTrue();
        assertThat(fixed.isCompileRan()).isTrue();
        assertThat(fixed.isTestsRan()).isTrue();
        assertThat(fixed.isStaticAnalysisRan()).isFalse();
        assertThat(fixed.getConfigSource()).isEqualTo("defaults");
    }

    @Test @Order(2)
    void compileFailureIsReportedWithCompilerOutput() {
        PatchValidation pv = validate(replaceBody("        return b == 0 ? 0 : a / b"));
        assertThat(pv.getStatus()).as(pv.getLogs()).isEqualTo(ValidationStatus.REJECTED_COMPILE);
        assertThat(pv.isTestsRan()).isFalse();
        assertThat(pv.getFailureReason()).contains("Calc.java").contains("';' expected");
    }

    @Test @Order(3)
    void malformedPatchIsRejectedWithoutRunningDocker() {
        PatchValidation policy = validate("change line 7 to handle zero");
        assertThat(policy.getStatus()).isEqualTo(ValidationStatus.REJECTED_POLICY);

        // Well-formed diff whose context does not exist in the snapshot
        PatchValidation apply = validate("--- a/" + FILE + "\n+++ b/" + FILE
                + "\n@@ -1,1 +1,1 @@\n-package nonexistent;\n+package demo;\n");
        assertThat(apply.getStatus()).isEqualTo(ValidationStatus.REJECTED_PATCH_APPLY);
        assertThat(apply.isCompileRan()).isFalse();
    }

    @Test @Order(4)
    void infiniteLoopTimesOutAndContainerIsKilled() throws Exception {
        Path ws = openDir("timeout-");
        Path scratch = openDir("timeout-scratch-");
        long start = System.nanoTime();
        SandboxExecutor.StepResult r = executor.runStep("test", "while true; do :; done",
                ws, scratch, cache(), 5);
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toSeconds();

        assertThat(r.timedOut()).isTrue();
        assertThat(r.passed()).isFalse();
        assertThat(elapsed).as("timeout must not be bypassed").isLessThan(45);
        assertThat(runningSandboxContainers()).isEmpty();
    }

    @Test @Order(5)
    void sandboxIsolation() throws Exception {
        Path ws = openDir("iso-");
        Path scratch = openDir("iso-scratch-");
        Path cache = cache();

        assertOutput("id -u", "65534", ws, scratch, cache);
        assertOutput("cat /sys/fs/cgroup/pids.max", "256", ws, scratch, cache);
        assertOutput("cat /sys/fs/cgroup/memory.max", String.valueOf(1024L * 1024 * 1024), ws, scratch, cache);
        // no network: outbound TCP and DNS both fail
        assertThat(step("bash -c 'echo > /dev/tcp/1.1.1.1/53'", ws, scratch, cache).passed()).isFalse();
        assertThat(step("getent hosts github.com", ws, scratch, cache).passed()).isFalse();
        // read-only root filesystem; no Docker socket; cache mounted read-only
        assertThat(step("touch /etc/pwned", ws, scratch, cache).passed()).isFalse();
        assertThat(step("test -e /var/run/docker.sock", ws, scratch, cache).passed()).isFalse();
        assertThat(step("touch /m2-cache/pwned", ws, scratch, cache).passed()).isFalse();
        // no host environment leaks into the container
        String env = step("env", ws, scratch, cache).output();
        assertThat(env).doesNotContain("GITHUB_TOKEN").doesNotContain("OPENAI_API_KEY")
                .doesNotContain(System.getProperty("user.home"));
    }

    @Test @Order(6)
    void workspacesAreCleanedUpAfterSuccessAndFailure() throws IOException {
        validate(replaceBody("        return b == 0 ? 0 : a / b"));   // failure
        validate(replaceBody("        return b == 0 ? 0 : a / b;"));  // success
        try (Stream<Path> s = Files.list(props.getWorkRoot())) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("attempt-")).toList()).isEmpty();
        }
    }

    private void assertOutput(String cmd, String expected, Path ws, Path scratch, Path cache) {
        SandboxExecutor.StepResult r = step(cmd, ws, scratch, cache);
        assertThat(r.output().strip()).as("%s -> %s", cmd, r).isEqualTo(expected);
    }

    private SandboxExecutor.StepResult step(String cmd, Path ws, Path scratch, Path cache) {
        return executor.runStep("probe", cmd, ws, scratch, cache, 60);
    }

    /** The container runs as uid 65534, so hand-made test dirs must be traversable/writable by it (Linux). */
    private Path openDir(String prefix) throws IOException {
        Files.createDirectories(props.getWorkRoot());
        Path d = Files.createTempDirectory(props.getWorkRoot(), prefix);
        try { Files.setPosixFilePermissions(d, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx")); }
        catch (UnsupportedOperationException ignored) {}
        return d;
    }

    private Path cache() throws IOException {
        Path c = props.getCacheRoot().resolve("m2").resolve("it_calc");
        Files.createDirectories(c);
        return c;
    }

    private static List<String> runningSandboxContainers() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(
                List.of("docker", "ps", "--filter", "name=" + DockerSandboxExecutor.CONTAINER_PREFIX, "--format", "{{.Names}}"),
                null, Duration.ofSeconds(20), ProcessRunner.inheritOnly(ProcessRunner.DOCKER_CLIENT_ENV), 4000, null);
        return r.output().lines().filter(l -> !l.isBlank()).toList();
    }
}
