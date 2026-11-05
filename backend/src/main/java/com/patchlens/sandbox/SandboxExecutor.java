package com.patchlens.sandbox;

import java.nio.file.Path;

/**
 * Executes build/test commands for untrusted code in an isolated environment.
 * The production implementation is {@link DockerSandboxExecutor}.
 */
public interface SandboxExecutor {

    record StepResult(String step, int exitCode, String output, boolean timedOut, long durationMs) {
        public boolean passed() { return exitCode == 0 && !timedOut; }
    }

    record Availability(boolean available, String reason) {
        public static Availability ok() { return new Availability(true, "available"); }
        public static Availability no(String reason) { return new Availability(false, reason); }
    }

    /** Whether the sandbox can run at all (enabled, daemon reachable, image present). */
    Availability availability();

    /**
     * Resolves build dependencies into {@code dependencyCache} with network access, using only
     * the build descriptors in {@code descriptorsOnlyDir} (no PR source code is present).
     */
    StepResult prefetchDependencies(Path descriptorsOnlyDir, Path dependencyCache, int timeoutSeconds);

    /**
     * Runs one step ({@code command} via {@code sh -c} inside the sandbox) against
     * {@code workspace} with networking disabled; {@code dependencyCache} is mounted read-only.
     */
    StepResult runStep(String step, String command, Path workspace, Path scratch,
                       Path dependencyCache, int timeoutSeconds);
}
