package com.patchlens.service;

import com.patchlens.model.*;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.repository.PatchValidationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Validates patch suggestions inside a Docker sandbox.
 *
 * Steps per patch:
 *   1. Create temp directory /tmp/patchlens-sandbox-{uuid}
 *   2. Write changed source files to temp dir
 *   3. Apply patch with {@code patch -p1 < patch.diff} (ProcessBuilder)
 *   4. Run Docker container: maven:3.9-eclipse-temurin-21 with compile + test commands
 *   5. Parse result → ValidationStatus
 *   6. Persist PatchValidation entity
 *   7. Delete temp directory
 *
 * In mock mode (ai.mode=mock), Docker execution is skipped; VALIDATED is returned.
 */
@Service
public class PatchValidationService {

    private static final Logger log = LoggerFactory.getLogger(PatchValidationService.class);

    /** Maximum chars of Docker output to store in logs (10k). */
    private static final int MAX_LOG_CHARS = 10_000;

    private final PatchValidationRepository validationRepository;
    private final PatchSuggestionRepository patchSuggestionRepository;
    private final GitHubService gitHubService;
    private final String aiMode;

    public PatchValidationService(PatchValidationRepository validationRepository,
                                  PatchSuggestionRepository patchSuggestionRepository,
                                  GitHubService gitHubService,
                                  @Value("${ai.mode:mock}") String aiMode) {
        this.validationRepository = validationRepository;
        this.patchSuggestionRepository = patchSuggestionRepository;
        this.gitHubService = gitHubService;
        this.aiMode = aiMode;
    }

    /**
     * Validates a patch against a real PR's base content fetched from GitHub.
     *
     * @param patch        the patch to validate
     * @param changedFiles changed files list (filenames + status used to determine fetch strategy)
     * @param owner        GitHub repo owner (used to fetch base-branch file content)
     * @param repo         GitHub repo name
     * @param baseRef      base branch name or SHA (e.g. "main", "abc123f") — files are fetched at this ref
     * @param config       build/test commands and timeouts from patchlens.yaml
     */
    public PatchValidation validate(PatchSuggestion patch,
                                    List<ChangedFile> changedFiles,
                                    String owner, String repo, String baseRef,
                                    ValidationConfig config) {
        if ("mock".equalsIgnoreCase(aiMode)) {
            return saveMockValidation(patch);
        }
        return runDockerValidation(patch, changedFiles, owner, repo, baseRef, config);
    }

    /**
     * Backward-compatible overload used by the eval harness (no GitHub context available).
     * Writes empty files to the sandbox; patch apply will succeed only for "added" file patches.
     */
    public PatchValidation validate(PatchSuggestion patch,
                                    List<ChangedFile> changedFiles,
                                    ValidationConfig config) {
        if ("mock".equalsIgnoreCase(aiMode)) {
            return saveMockValidation(patch);
        }
        return runDockerValidation(patch, changedFiles, null, null, null, config);
    }

    // =====================================================================
    // Mock mode
    // =====================================================================

    private PatchValidation saveMockValidation(PatchSuggestion patch) {
        PatchValidation pv = new PatchValidation(
                patch.getId(), true, true, true, true,
                "[Mock] Validation skipped in mock mode.", 0L
        );
        pv = validationRepository.save(pv);
        patch.setStatus(ValidationStatus.VALIDATED);
        patchSuggestionRepository.save(patch);
        return pv;
    }

    // =====================================================================
    // Docker validation
    // =====================================================================

    private PatchValidation runDockerValidation(PatchSuggestion patch,
                                                 List<ChangedFile> changedFiles,
                                                 String owner, String repo, String baseRef,
                                                 ValidationConfig config) {
        Path sandboxDir = Path.of("/tmp/patchlens-sandbox-" + UUID.randomUUID());
        long start = System.currentTimeMillis();

        boolean patchApplied        = false;
        boolean compilePassed        = false;
        boolean staticAnalysisPassed = false;
        boolean testsPassed          = false;
        StringBuilder logs = new StringBuilder();

        try {
            Files.createDirectories(sandboxDir);

            // Write pre-patch file content to sandbox
            if (owner != null && repo != null && baseRef != null) {
                writeFilesFromGitHub(changedFiles, owner, repo, baseRef, sandboxDir, logs);
            } else {
                writeEmptyFiles(changedFiles, sandboxDir);
            }

            // Write patch file
            Path patchFile = sandboxDir.resolve("patch.diff");
            Files.writeString(patchFile, patch.getPatchText());

            // Apply patch
            ProcessResult applyResult = runProcess(
                    List.of("patch", "-p1", "--input=patch.diff"),
                    sandboxDir, 30
            );
            logs.append("=== patch apply ===\n").append(truncate(applyResult.output()));
            patchApplied = applyResult.exitCode() == 0;

            if (patchApplied) {
                // Run Docker: compile → static analysis → test (sequential, stop on first failure)
                // Each step emits a sentinel marker so we can identify exactly which step passed.
                String dockerCmd = buildDockerCommand(sandboxDir, config);
                int totalTimeout = config.getTimeouts().compileSeconds()
                        + config.getTimeouts().staticAnalysisSeconds()
                        + config.getTimeouts().testSeconds() + 30;
                ProcessResult dockerResult = runProcess(
                        List.of("bash", "-c", dockerCmd), sandboxDir, totalTimeout);
                String dockerOutput = dockerResult.output();
                logs.append("\n=== docker run ===\n").append(truncate(dockerOutput));

                compilePassed        = dockerOutput.contains("::COMPILE_OK::");
                staticAnalysisPassed = !config.getStaticAnalysis().isEnabled()
                        || dockerOutput.contains("::STATIC_OK::");
                testsPassed          = dockerOutput.contains("::TEST_OK::");
            }

        } catch (Exception e) {
            logs.append("\n=== error ===\n").append(e.getMessage());
            log.warn("Docker validation error for patch {}: {}", patch.getId(), e.getMessage());
        } finally {
            deleteSandbox(sandboxDir);
        }

        long durationMs = System.currentTimeMillis() - start;

        // Determine status (ordered: patch → compile → static analysis → tests)
        ValidationStatus status;
        if (!patchApplied)            status = ValidationStatus.REJECTED_PATCH_APPLY;
        else if (!compilePassed)      status = ValidationStatus.REJECTED_COMPILE;
        else if (!staticAnalysisPassed) status = ValidationStatus.REJECTED_STATIC_ANALYSIS;
        else if (!testsPassed)        status = ValidationStatus.REJECTED_TEST;
        else                          status = ValidationStatus.VALIDATED;

        PatchValidation pv = new PatchValidation(
                patch.getId(), patchApplied, compilePassed, staticAnalysisPassed, testsPassed,
                logs.toString(), durationMs
        );
        pv = validationRepository.save(pv);

        patch.setStatus(status);
        patchSuggestionRepository.save(patch);

        return pv;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Fetches each file's pre-patch content from GitHub at {@code baseRef} and writes it to
     * the sandbox so that {@code patch -p1} has real source to apply against.
     *
     * Strategy by file status:
     *   modified / renamed / removed → fetch from baseRef (file existed before the PR)
     *   added                        → file didn't exist at base; write empty so patch can create it
     *   binary (patch == null)       → skip entirely
     */
    private void writeFilesFromGitHub(List<ChangedFile> files,
                                       String owner, String repo, String baseRef,
                                       Path sandboxDir, StringBuilder logs) throws IOException {
        for (ChangedFile f : files) {
            if (f.patch() == null) continue; // binary — patch can't apply anyway

            Path dest = sandboxDir.resolve(f.filename());
            Files.createDirectories(dest.getParent());

            if ("added".equals(f.status())) {
                // File is new — write empty so patch -p1 can create it via the diff
                Files.write(dest, new byte[0]);
                continue;
            }

            // modified / renamed / removed: fetch the base-branch content
            Optional<byte[]> content = gitHubService.fetchFileContent(owner, repo, f.filename(), baseRef);
            if (content.isPresent()) {
                Files.write(dest, content.get());
            } else {
                // 404 at baseRef — treat as added (shouldn't happen for modified/removed, but be safe)
                log.warn("File {} not found at baseRef {} — writing empty", f.filename(), baseRef);
                logs.append("[warn] ").append(f.filename()).append(" not found at ").append(baseRef).append("\n");
                Files.write(dest, new byte[0]);
            }
        }
    }

    /**
     * Fallback used when no GitHub context is available (eval harness with fixture PRs).
     * Writes empty files; patch apply will only succeed for "added" file diffs.
     */
    private void writeEmptyFiles(List<ChangedFile> files, Path sandboxDir) throws IOException {
        for (ChangedFile f : files) {
            if (f.patch() == null) continue;
            Path dest = sandboxDir.resolve(f.filename());
            Files.createDirectories(dest.getParent());
            Files.write(dest, new byte[0]);
        }
    }

    /**
     * Builds a Docker command that runs compile → static analysis → tests in sequence.
     * Each step emits a sentinel line on success (e.g. {@code ::COMPILE_OK::}) so the
     * caller can determine exactly which step passed without relying on heuristics.
     * {@code set -e} ensures the script stops at the first failing step.
     */
    private String buildDockerCommand(Path sandboxDir, ValidationConfig config) {
        String compile = config.getBuild().command().replace("\"", "\\\"");
        String test    = config.getTests().command().replace("\"", "\\\"");

        StringBuilder script = new StringBuilder("set -e; ");
        script.append(compile).append(" && echo ::COMPILE_OK::; ");

        if (config.getStaticAnalysis().isEnabled()) {
            String sa = config.getStaticAnalysis().command().replace("\"", "\\\"");
            script.append(sa).append(" && echo ::STATIC_OK::; ");
        }

        script.append(test).append(" && echo ::TEST_OK::");

        return String.format(
                "docker run --rm --cpus=1 --memory=512m -v %s:/workspace " +
                "maven:3.9-eclipse-temurin-21 bash -c \"%s\"",
                sandboxDir.toAbsolutePath(), script
        );
    }

    private record ProcessResult(int exitCode, String output) {}

    private ProcessResult runProcess(List<String> command, Path workDir, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(1, output + "\n[TIMEOUT after " + timeoutSeconds + "s]");
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > MAX_LOG_CHARS ? text.substring(0, MAX_LOG_CHARS) + "\n...(truncated)" : text;
    }

    private void deleteSandbox(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to delete sandbox dir {}: {}", dir, e.getMessage());
        }
    }
}
