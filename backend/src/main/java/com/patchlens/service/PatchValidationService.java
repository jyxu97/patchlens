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
    private final String aiMode;

    public PatchValidationService(PatchValidationRepository validationRepository,
                                  PatchSuggestionRepository patchSuggestionRepository,
                                  @Value("${ai.mode:mock}") String aiMode) {
        this.validationRepository = validationRepository;
        this.patchSuggestionRepository = patchSuggestionRepository;
        this.aiMode = aiMode;
    }

    /**
     * Validates a patch suggestion inside a Docker sandbox.
     *
     * @param patch        the patch to validate
     * @param changedFiles the PR's changed files (their content is written to the sandbox)
     * @param config       build/test commands and timeouts from patchlens.yaml
     * @return the persisted PatchValidation result
     */
    public PatchValidation validate(PatchSuggestion patch,
                                    List<ChangedFile> changedFiles,
                                    ValidationConfig config) {
        if ("mock".equalsIgnoreCase(aiMode)) {
            return saveMockValidation(patch);
        }
        return runDockerValidation(patch, changedFiles, config);
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
                                                 ValidationConfig config) {
        Path sandboxDir = Path.of("/tmp/patchlens-sandbox-" + UUID.randomUUID());
        long start = System.currentTimeMillis();

        boolean patchApplied = false;
        boolean compilePassed = false;
        boolean testsPassed   = false;
        StringBuilder logs = new StringBuilder();

        try {
            Files.createDirectories(sandboxDir);

            // Write changed files to sandbox
            writeFilesToSandbox(changedFiles, sandboxDir);

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
                // Run Docker: compile + test
                String dockerCmd = buildDockerCommand(sandboxDir, config);
                ProcessResult dockerResult = runProcess(
                        List.of("bash", "-c", dockerCmd),
                        sandboxDir,
                        config.getTimeouts().compileSeconds() + config.getTimeouts().testSeconds() + 30
                );
                logs.append("\n=== docker run ===\n").append(truncate(dockerResult.output()));

                if (dockerResult.exitCode() == 0) {
                    compilePassed = true;
                    testsPassed   = true;
                } else {
                    // Heuristic: if compile failed it won't reach test phase
                    String output = dockerResult.output().toLowerCase();
                    compilePassed = !output.contains("compilation failure")
                            && !output.contains("build failure")
                            && !output.contains("error:");
                    testsPassed = false;
                }
            }

        } catch (Exception e) {
            logs.append("\n=== error ===\n").append(e.getMessage());
            log.warn("Docker validation error for patch {}: {}", patch.getId(), e.getMessage());
        } finally {
            deleteSandbox(sandboxDir);
        }

        long durationMs = System.currentTimeMillis() - start;

        // Determine status
        ValidationStatus status;
        if (!patchApplied)        status = ValidationStatus.REJECTED_PATCH_APPLY;
        else if (!compilePassed)  status = ValidationStatus.REJECTED_COMPILE;
        else if (!testsPassed)    status = ValidationStatus.REJECTED_TEST;
        else                      status = ValidationStatus.VALIDATED;

        PatchValidation pv = new PatchValidation(
                patch.getId(), patchApplied, compilePassed, true, testsPassed,
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

    private void writeFilesToSandbox(List<ChangedFile> files, Path sandboxDir) throws IOException {
        for (ChangedFile f : files) {
            if (f.patch() == null) continue; // binary file — skip
            Path dest = sandboxDir.resolve(f.filename());
            Files.createDirectories(dest.getParent());
            // Write a placeholder — in production, the actual file content
            // would be fetched from GitHub via Contents API and Base64-decoded.
            // The patch -p1 command will apply the unified diff on top of this.
            Files.writeString(dest, "// placeholder for " + f.filename() + "\n");
        }
    }

    private String buildDockerCommand(Path sandboxDir, ValidationConfig config) {
        return String.format(
                "docker run --rm --cpus=1 --memory=512m -v %s:/workspace " +
                "maven:3.9-eclipse-temurin-21 bash -c \"%s && %s\"",
                sandboxDir.toAbsolutePath(),
                config.getBuild().command().replace("\"", "\\\""),
                config.getTests().command().replace("\"", "\\\"")
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
