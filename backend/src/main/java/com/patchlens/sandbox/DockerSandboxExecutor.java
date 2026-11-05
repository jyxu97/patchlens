package com.patchlens.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Docker-based sandbox for untrusted PR builds and tests.
 *
 * <p>Isolation applied to every build/test container:
 * <ul>
 *   <li>{@code --network none} — no network during untrusted execution</li>
 *   <li>non-root {@code --user}, {@code --cap-drop ALL}, {@code no-new-privileges}</li>
 *   <li>{@code --read-only} root filesystem with a size-limited {@code /tmp} tmpfs</li>
 *   <li>CPU, memory (swap disabled) and PID limits</li>
 *   <li>only three bind mounts: the disposable workspace, a disposable scratch dir, and the
 *       per-repository dependency cache <em>read-only</em>; never the Docker socket or host home</li>
 *   <li>no host environment is forwarded; only fixed variables are set</li>
 *   <li>a wall-clock timeout that kills the container (not just the CLI client)</li>
 * </ul>
 *
 * <p>Dependencies are resolved beforehand by {@link #prefetchDependencies}, which does use the
 * network but sees only the build descriptors (pom files) — no PR source code. Maven then runs
 * offline against a chained local repository: a per-run writable repo in scratch with the shared
 * cache as a read-only tail ({@code maven.repo.local.tail}, Maven 3.9+), so a malicious build
 * cannot poison the cache used by later validations.
 */
@Component
public class DockerSandboxExecutor implements SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxExecutor.class);
    private static final Duration PROBE_TTL = Duration.ofSeconds(60);
    /** Prefix of every sandbox container name (distinct from docker-compose service names). */
    public static final String CONTAINER_PREFIX = "patchlens-sbx-";

    private final SandboxProperties props;
    private volatile Availability cachedAvailability;
    private volatile long availabilityCheckedAt;

    public DockerSandboxExecutor(SandboxProperties props) {
        this.props = props;
    }

    @Override
    public Availability availability() {
        if (!props.isEnabled()) return Availability.no("sandbox disabled (patchlens.sandbox.enabled=false)");
        long now = System.currentTimeMillis();
        Availability cached = cachedAvailability;
        if (cached != null && now - availabilityCheckedAt < PROBE_TTL.toMillis()) return cached;
        Availability fresh = probe();
        cachedAvailability = fresh;
        availabilityCheckedAt = now;
        return fresh;
    }

    private Availability probe() {
        try {
            var version = docker(List.of("version", "--format", "{{.Server.Version}}"), Duration.ofSeconds(15));
            if (version.exitCode() != 0) return Availability.no("Docker daemon unreachable: " + firstLine(version.output()));
            var image = docker(List.of("image", "inspect", "--format", "{{.Id}}", props.getImage()), Duration.ofSeconds(15));
            if (image.exitCode() != 0) {
                log.info("Sandbox image {} not present — pulling", props.getImage());
                var pull = docker(List.of("pull", props.getImage()), Duration.ofMinutes(10));
                if (pull.exitCode() != 0) return Availability.no("Cannot pull sandbox image " + props.getImage());
            }
            return Availability.ok();
        } catch (Exception e) {
            return Availability.no("Docker CLI not usable: " + e.getMessage());
        }
    }

    @Override
    public StepResult prefetchDependencies(Path descriptorsOnlyDir, Path dependencyCache, int timeoutSeconds) {
        List<String> args = baseRunArgs("prefetch");
        // Network is required to download dependencies; no PR source code is mounted.
        args.addAll(List.of(
                "--mount", bind(descriptorsOnlyDir, "/workspace", false),
                "--mount", bind(dependencyCache, "/m2-cache", false),
                "-e", "HOME=/tmp/home",
                "-e", "MAVEN_ARGS=-B -Dmaven.repo.local=/m2-cache",
                props.getImage(), "-c", PREFETCH_SCRIPT));
        return execute("prefetch", args, timeoutSeconds);
    }

    /**
     * Resolves project dependencies and the plugins used by the default lifecycle. A trusted,
     * synthetic test class is compiled and run so that Surefire resolves its JUnit provider
     * (which it otherwise only downloads lazily at test time, breaking offline runs).
     */
    static final String PREFETCH_SCRIPT = String.join("\n",
            "set -e",
            "umask 0000",
            "mvn -q dependency:go-offline",
            "if [ -d src/test/java ]; then",
            "  mkdir -p src/test/java/patchlensprefetch",
            "  printf 'package patchlensprefetch;\\npublic class PatchlensPrefetchTest {\\n  @org.junit.jupiter.api.Test public void ok() {}\\n}\\n' "
                    + "> src/test/java/patchlensprefetch/PatchlensPrefetchTest.java",
            "  mvn -q test -Dtest=PatchlensPrefetchTest -Dsurefire.failIfNoSpecifiedTests=false || true",
            "fi");

    @Override
    public StepResult runStep(String step, String command, Path workspace, Path scratch,
                              Path dependencyCache, int timeoutSeconds) {
        List<String> args = baseRunArgs(step);
        args.addAll(List.of(
                "--network", "none",
                "--mount", bind(workspace, "/workspace", false),
                "--mount", bind(scratch, "/scratch", false),
                "--mount", bind(dependencyCache, "/m2-cache", true),
                "-e", "HOME=/scratch/home",
                "-e", "MAVEN_ARGS=-B -o -Dmaven.repo.local=/scratch/m2 -Dmaven.repo.local.tail=/m2-cache",
                // umask: files created by the unprivileged container user must stay deletable by the host
                props.getImage(), "-c", "umask 0000\n" + command));
        return execute(step, args, timeoutSeconds);
    }

    /** Arguments common to every container; {@code sh} is the entrypoint, the script is one argv element. */
    private List<String> baseRunArgs(String step) {
        List<String> args = new ArrayList<>(List.of(
                "run", "--rm",
                "--name", CONTAINER_PREFIX + step + "-" + UUID.randomUUID(),
                "--user", props.getUser(),
                "--cpus", props.getCpus(),
                "--memory", props.getMemory(),
                "--memory-swap", props.getMemory(),
                "--pids-limit", String.valueOf(props.getPidsLimit()),
                "--read-only",
                "--tmpfs", "/tmp:rw,exec,size=" + props.getTmpfsSize(),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--workdir", "/workspace",
                "--entrypoint", "sh"));
        return args;
    }

    static String bind(Path src, String dst, boolean readOnly) {
        String s = src.toAbsolutePath().normalize().toString();
        if (s.contains(",") || s.contains("=")) throw new IllegalArgumentException("Unsupported characters in mount path: " + s);
        return "type=bind,src=" + s + ",dst=" + dst + (readOnly ? ",readonly" : "");
    }

    private StepResult execute(String step, List<String> dockerArgs, int timeoutSeconds) {
        String name = dockerArgs.get(dockerArgs.indexOf("--name") + 1);
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getDockerBinary());
        cmd.addAll(dockerArgs);
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, null, Duration.ofSeconds(timeoutSeconds),
                    clientEnv(), props.getMaxLogChars(), () -> killContainer(name));
            StepResult result = new StepResult(step, r.exitCode(), r.output(), r.timedOut(), r.durationMs());
            com.patchlens.observability.PatchLensMetrics.sandboxStep(step.startsWith("probe") ? "probe" : step, result.passed(), r.timedOut(), r.durationMs());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killContainer(name);
            return new StepResult(step, -1, "Interrupted", false, 0);
        } catch (Exception e) {
            return new StepResult(step, -1, "Failed to start docker: " + e.getMessage(), false, 0);
        } finally {
            removeContainer(name);
        }
    }

    private void killContainer(String name) {
        try { docker(List.of("kill", name), Duration.ofSeconds(20)); } catch (Exception ignored) {}
    }

    private void removeContainer(String name) {
        try { docker(List.of("rm", "-f", name), Duration.ofSeconds(20)); } catch (Exception ignored) {}
    }

    private ProcessRunner.Result docker(List<String> args, Duration timeout) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getDockerBinary());
        cmd.addAll(args);
        return ProcessRunner.run(cmd, null, timeout, clientEnv(), 4000, null);
    }

    private static Map<String, String> clientEnv() {
        return ProcessRunner.inheritOnly(ProcessRunner.DOCKER_CLIENT_ENV);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).strip();
    }
}
