package com.patchlens.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a host process with a hard wall-clock timeout.
 *
 * <p>Output is redirected to a temporary file instead of being read through a pipe. Reading a
 * pipe with {@code readAllBytes()} before {@code waitFor(timeout)} blocks until the child exits,
 * which lets a process that never terminates bypass the timeout entirely; with a file there is
 * nothing for the parent to block on.
 *
 * <p>Commands are passed as argv lists (no shell), and the child receives only the explicitly
 * provided environment — host credentials in the parent environment are not inherited.
 */
public final class ProcessRunner {

    public record Result(int exitCode, String output, boolean timedOut, long durationMs) {}

    /** Host environment variables a Docker CLI needs to reach its daemon. Nothing else is passed. */
    public static final List<String> DOCKER_CLIENT_ENV = List.of(
            "PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG",
            "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY", "TMPDIR");

    private ProcessRunner() {}

    /**
     * @param onTimeout invoked before the process is destroyed when the timeout elapses
     *                  (e.g. {@code docker kill <name>} — killing the CLI client alone would
     *                  leave the container running)
     */
    public static Result run(List<String> command, Path workDir, Duration timeout,
                             Map<String, String> env, int maxOutputChars, Runnable onTimeout)
            throws IOException, InterruptedException {
        Path out = Files.createTempFile("patchlens-proc-", ".log");
        long start = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null) pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(out.toFile());
            pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
            pb.environment().clear();
            pb.environment().putAll(env);

            Process process = pb.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = false;
            if (!finished) {
                timedOut = true;
                if (onTimeout != null) {
                    try { onTimeout.run(); } catch (RuntimeException ignored) {}
                }
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            }
            int exit = process.isAlive() ? -1 : process.exitValue();
            String output = readBounded(out, maxOutputChars);
            if (timedOut) output += "\n[TIMEOUT after " + timeout.toSeconds() + "s]";
            return new Result(timedOut ? -1 : exit, output, timedOut,
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /** Builds a child environment from the allow-listed variables of the current process. */
    public static Map<String, String> inheritOnly(List<String> names) {
        Map<String, String> env = new java.util.HashMap<>();
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null) env.put(n, v);
        }
        env.putIfAbsent("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        return env;
    }

    /** Keeps the head and the tail of large outputs — build errors are usually at the end. */
    static String readBounded(Path file, int maxChars) throws IOException {
        String s = Files.readString(file, StandardCharsets.UTF_8);
        if (s.length() <= maxChars) return s;
        int head = maxChars / 4;
        int tail = maxChars - head;
        return s.substring(0, head) + "\n...(" + (s.length() - maxChars) + " chars omitted)...\n"
                + s.substring(s.length() - tail);
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }
}
