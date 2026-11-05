package com.patchlens.service;

import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.PatchValidation;
import com.patchlens.model.PatchValidation.Stage;
import com.patchlens.model.ValidationStatus;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.repository.PatchValidationRepository;
import com.patchlens.sandbox.*;
import com.patchlens.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * Validates one patch attempt against a pinned repository snapshot inside the sandbox.
 *
 * <p>Each call:
 * <ol>
 *   <li>re-checks deterministic {@link PatchPolicy} (format, size, allowed files)</li>
 *   <li>materialises the snapshot into a <em>fresh</em> workspace (never reused across attempts)</li>
 *   <li>loads {@code patchlens.yaml} from the pristine snapshot (before the patch is applied)</li>
 *   <li>applies the patch in-process with {@link UnifiedDiff}</li>
 *   <li>prefetches dependencies (network on, descriptors only), then runs compile, optional
 *       static analysis and tests with networking disabled</li>
 *   <li>persists a {@link PatchValidation} row with per-step results and the error output</li>
 *   <li>deletes the workspace</li>
 * </ol>
 *
 * <p>If the sandbox cannot run, the result is {@link ValidationStatus#NOT_VALIDATED} (or
 * {@link ValidationStatus#VALIDATION_ERROR} for infrastructure failures) — never
 * {@code VALIDATED}. There is no mock shortcut.
 */
@Service
public class PatchValidationService {

    private static final Logger log = LoggerFactory.getLogger(PatchValidationService.class);
    private static final int MAX_REASON_CHARS = 2_000;

    private final PatchValidationRepository validationRepository;
    private final PatchSuggestionRepository patchSuggestionRepository;
    private final SandboxExecutor sandbox;
    private final SandboxProperties props;
    private final Semaphore concurrency;
    private final Map<String, Object> prefetchLocks = new ConcurrentHashMap<>();

    public PatchValidationService(PatchValidationRepository validationRepository,
                                  PatchSuggestionRepository patchSuggestionRepository,
                                  SandboxExecutor sandbox,
                                  SandboxProperties props) {
        this.validationRepository = validationRepository;
        this.patchSuggestionRepository = patchSuggestionRepository;
        this.sandbox = sandbox;
        this.props = props;
        this.concurrency = new Semaphore(props.getMaxConcurrent(), true);
    }

    public SandboxExecutor.Availability availability() {
        return sandbox.availability();
    }

    /**
     * Validates {@code patch} (its current text) against {@code snapshot} and persists the outcome.
     * Also updates {@code patch.status}.
     *
     * @param allowedFiles  files the patch may modify (policy)
     * @param attemptNumber 0 for the initial candidate, 1..n for repairs
     */
    public PatchValidation validate(PatchSuggestion patch, RepositorySnapshot snapshot,
                                    Set<String> allowedFiles, int attemptNumber) {
        long start = System.currentTimeMillis();
        PatchValidation pv = new PatchValidation();
        pv.setPatchId(patch.getId());
        pv.setAttemptNumber(attemptNumber);
        pv.setPatchText(patch.getPatchText());
        pv.setRevision(snapshot.revision());
        StringBuilder logs = new StringBuilder();

        try {
            run(patch, snapshot, allowedFiles, pv, logs);
        } catch (Exception e) {
            log.warn("Validation of patch {} failed unexpectedly: {}", patch.getId(), Redactor.redact(e.getMessage()));
            finish(pv, ValidationStatus.VALIDATION_ERROR, Stage.SANDBOX, "Unexpected validation error: " + e.getMessage());
        }

        pv.setLogs(Redactor.redactTail(logs.toString(), props.getMaxLogChars()));
        pv.setDurationMs(System.currentTimeMillis() - start);
        com.patchlens.observability.PatchLensMetrics.validationOutcome(String.valueOf(pv.getStatus()),
                pv.getFailureStage() == null ? null : pv.getFailureStage().name());
        pv = validationRepository.save(pv);

        patch.setStatus(pv.getStatus());
        patchSuggestionRepository.save(patch);
        return pv;
    }

    private void run(PatchSuggestion patch, RepositorySnapshot snapshot, Set<String> allowedFiles,
                     PatchValidation pv, StringBuilder logs) throws Exception {
        // 1 — deterministic policy (cheap, no sandbox needed)
        Optional<String> policyError = PatchPolicy.check(patch.getPatchText(), allowedFiles);
        if (policyError.isPresent()) {
            finish(pv, ValidationStatus.REJECTED_POLICY, Stage.POLICY, policyError.get());
            return;
        }

        // 2 — sandbox must actually be able to run; otherwise the patch is explicitly unvalidated
        SandboxExecutor.Availability availability = sandbox.availability();
        if (!availability.available()) {
            finish(pv, ValidationStatus.NOT_VALIDATED, Stage.SANDBOX, "Sandbox unavailable: " + availability.reason());
            return;
        }

        concurrency.acquire();
        Path attemptDir = null;
        try {
            Files.createDirectories(props.getWorkRoot());
            attemptDir = Files.createTempDirectory(props.getWorkRoot(), "attempt-");
            Path workspace = Files.createDirectory(attemptDir.resolve("workspace"));
            Path scratch = Files.createDirectory(attemptDir.resolve("scratch"));

            // 3 — fresh snapshot at the pinned revision
            try {
                snapshot.materialize(workspace);
            } catch (IOException | RuntimeException e) {
                finish(pv, ValidationStatus.VALIDATION_ERROR, Stage.SNAPSHOT,
                        "Could not materialise snapshot " + snapshot.describe() + ": " + e.getMessage());
                return;
            }

            // 4 — repository config from the pristine tree (the patch cannot change it)
            ValidationConfigLoader.Loaded loaded;
            try {
                loaded = ValidationConfigLoader.load(workspace, props.getMaxStepSeconds());
            } catch (Exception e) {
                finish(pv, ValidationStatus.VALIDATION_ERROR, Stage.CONFIG, "Invalid patchlens.yaml: " + e.getMessage());
                return;
            }
            ValidationConfig config = loaded.config();
            pv.setConfigSource(loaded.source());
            if (!Files.isRegularFile(workspace.resolve(config.getBuildFile()))) {
                finish(pv, ValidationStatus.VALIDATION_ERROR, Stage.CONFIG,
                        "Build file '" + config.getBuildFile() + "' not found at revision " + snapshot.revision());
                return;
            }

            // 5 — apply the patch in-process
            try {
                UnifiedDiff.apply(workspace, UnifiedDiff.parse(patch.getPatchText()));
                pv.setPatchApplied(true);
                logs.append("=== patch apply ===\nok\n");
            } catch (UnifiedDiff.PatchException e) {
                logs.append("=== patch apply ===\n").append(e.getMessage()).append('\n');
                finish(pv, ValidationStatus.REJECTED_PATCH_APPLY, Stage.PATCH_APPLY, "Patch did not apply: " + e.getMessage());
                return;
            }

            // 6 — dependencies (network on, descriptors only), cached per repository
            Path depCache = dependencyCache(snapshot.dependencyCacheKey());
            if ("pom.xml".equals(config.getBuildFile())) {
                SandboxExecutor.StepResult prefetch = prefetchIfNeeded(workspace, attemptDir, depCache);
                if (prefetch != null) {
                    logs.append("=== dependency prefetch ===\n").append(prefetch.output()).append('\n');
                    if (!prefetch.passed()) {
                        finish(pv, ValidationStatus.VALIDATION_ERROR, Stage.DEPENDENCIES,
                                "Dependency resolution failed" + (prefetch.timedOut() ? " (timed out)" : "")
                                        + ": " + tail(prefetch.output()));
                        return;
                    }
                }
            }
            makeWorldWritable(workspace);
            makeWorldWritable(scratch);

            // 7 — compile → static analysis → tests, network disabled
            ValidationConfig.Timeouts t = config.getTimeouts();
            pv.setCompileRan(true);
            SandboxExecutor.StepResult compile = step("compile", config.getBuild().command(), workspace, scratch, depCache, t.compileSeconds(), logs);
            if (!compile.passed()) {
                pv.setTimedOut(compile.timedOut());
                if (isOfflineDependencyFailure(compile.output())) {
                    finish(pv, ValidationStatus.VALIDATION_ERROR, Stage.DEPENDENCIES,
                            "A dependency was not available offline: " + tail(compile.output()));
                } else {
                    finish(pv, ValidationStatus.REJECTED_COMPILE, Stage.COMPILE,
                            (compile.timedOut() ? "Compilation timed out after " + t.compileSeconds() + "s. " : "Compilation failed. ")
                                    + tail(compile.output()));
                }
                return;
            }
            pv.setCompilePassed(true);

            if (config.getStaticAnalysis().isEnabled()) {
                pv.setStaticAnalysisRan(true);
                SandboxExecutor.StepResult sa = step("static-analysis", config.getStaticAnalysis().command(),
                        workspace, scratch, depCache, t.staticAnalysisSeconds(), logs);
                if (!sa.passed()) {
                    pv.setTimedOut(sa.timedOut());
                    finish(pv, ValidationStatus.REJECTED_STATIC_ANALYSIS, Stage.STATIC_ANALYSIS,
                            "Static analysis failed. " + tail(sa.output()));
                    return;
                }
                pv.setStaticAnalysisPassed(true);
            } else {
                pv.setStaticAnalysisRan(false);
                pv.setStaticAnalysisPassed(true); // not configured — vacuously satisfied, recorded as not run
            }

            pv.setTestsRan(true);
            SandboxExecutor.StepResult tests = step("test", config.getTests().command(), workspace, scratch, depCache, t.testSeconds(), logs);
            if (!tests.passed()) {
                pv.setTimedOut(tests.timedOut());
                if (isOfflineDependencyFailure(tests.output())) {
                    finish(pv, ValidationStatus.VALIDATION_ERROR, Stage.DEPENDENCIES,
                            "A test dependency was not available offline: " + tail(tests.output()));
                } else {
                    finish(pv, ValidationStatus.REJECTED_TEST, Stage.TEST,
                            (tests.timedOut() ? "Tests timed out after " + t.testSeconds() + "s. " : "Tests failed. ")
                                    + tail(tests.output()));
                }
                return;
            }
            pv.setTestsPassed(true);
            finish(pv, ValidationStatus.VALIDATED, Stage.NONE, null);
        } finally {
            concurrency.release();
            if (attemptDir != null) deleteRecursively(attemptDir);
        }
    }

    private SandboxExecutor.StepResult step(String name, String command, Path workspace, Path scratch,
                                            Path depCache, int timeoutSeconds, StringBuilder logs) {
        SandboxExecutor.StepResult r = sandbox.runStep(name, command, workspace, scratch, depCache, timeoutSeconds);
        logs.append("=== ").append(name).append(" (exit ").append(r.exitCode())
            .append(r.timedOut() ? ", timed out" : "").append(", ").append(r.durationMs()).append("ms) ===\n")
            .append(r.output()).append('\n');
        return r;
    }

    private static void finish(PatchValidation pv, ValidationStatus status, Stage stage, String reason) {
        pv.setStatus(status);
        pv.setFailureStage(stage);
        pv.setFailureReason(reason == null ? null : Redactor.redactTail(reason, MAX_REASON_CHARS));
    }

    /** Maven's message when an artifact is missing from the offline local repository. */
    static boolean isOfflineDependencyFailure(String output) {
        if (output == null) return false;
        return output.contains("in offline mode")
                || output.contains("has not been downloaded from it before")
                || output.contains("Cannot access central");
    }

    private String tail(String s) {
        return Redactor.redactTail(s, MAX_REASON_CHARS);
    }

    // =====================================================================
    // Dependency cache
    // =====================================================================

    private Path dependencyCache(String key) throws IOException {
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = props.getCacheRoot().resolve("m2").resolve(safe);
        Files.createDirectories(dir);
        makeWorldWritable(dir);
        return dir;
    }

    /**
     * Runs the prefetch container if this repository's set of build descriptors has not been
     * resolved before. Returns null when the cache is already warm.
     */
    private SandboxExecutor.StepResult prefetchIfNeeded(Path workspace, Path attemptDir, Path depCache) throws IOException {
        List<Path> poms;
        try (Stream<Path> walk = Files.walk(workspace)) {
            poms = walk.filter(p -> p.getFileName().toString().equals("pom.xml"))
                       .filter(Files::isRegularFile).sorted().toList();
        }
        String fingerprint = fingerprint(workspace, poms);
        Path marker = depCache.resolve(".prefetched-" + fingerprint);

        Object lock = prefetchLocks.computeIfAbsent(depCache.toString(), k -> new Object());
        synchronized (lock) {
            if (Files.exists(marker)) return null;

            Path descriptors = Files.createDirectory(attemptDir.resolve("descriptors"));
            for (Path pom : poms) {
                Path rel = workspace.relativize(pom);
                Path dest = descriptors.resolve(rel.toString());
                Files.createDirectories(dest.getParent());
                Files.copy(pom, dest);
                if (Files.isDirectory(pom.getParent().resolve("src/test/java"))) {
                    Files.createDirectories(dest.getParent().resolve("src/test/java"));
                }
            }
            makeWorldWritable(descriptors);
            SandboxExecutor.StepResult r = sandbox.prefetchDependencies(descriptors, depCache, props.getPrefetchTimeoutSeconds());
            if (r.passed()) Files.writeString(marker, java.time.Instant.now() + "\n");
            return r;
        }
    }


    private static String fingerprint(Path root, List<Path> files) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path f : files) {
                md.update(root.relativize(f).toString().getBytes(StandardCharsets.UTF_8));
                md.update(Files.readAllBytes(f));
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // =====================================================================
    // Filesystem helpers
    // =====================================================================

    /** The container runs as an unprivileged uid, so the disposable dirs must be writable by it. */
    private static void makeWorldWritable(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !Files.isSymbolicLink(p)).forEach(p -> {
                try {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                            Files.isDirectory(p) ? "rwxrwxrwx" : "rw-rw-rw-"));
                } catch (UnsupportedOperationException | IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Failed to delete sandbox dir {}: {}", dir, e.getMessage());
        }
    }
}
