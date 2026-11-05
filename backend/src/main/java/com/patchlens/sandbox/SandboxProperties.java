package com.patchlens.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Sandbox limits and locations ({@code patchlens.sandbox.*} properties).
 * Every limit applies to untrusted PR code executing inside the validation container.
 */
@Component
public class SandboxProperties {

    private final boolean enabled;
    private final String dockerBinary;
    private final String image;
    private final Path workRoot;
    private final Path cacheRoot;
    private final String cpus;
    private final String memory;
    private final int pidsLimit;
    private final String user;
    private final String tmpfsSize;
    private final int maxConcurrent;
    private final int maxStepSeconds;
    private final int prefetchTimeoutSeconds;
    private final int maxLogChars;

    public SandboxProperties(
            @Value("${patchlens.sandbox.enabled:true}") boolean enabled,
            @Value("${patchlens.sandbox.docker-binary:docker}") String dockerBinary,
            @Value("${patchlens.sandbox.image:maven:3.9.9-eclipse-temurin-21}") String image,
            @Value("${patchlens.sandbox.work-root:${java.io.tmpdir}/patchlens-sandbox}") String workRoot,
            @Value("${patchlens.sandbox.cache-root:${java.io.tmpdir}/patchlens-cache}") String cacheRoot,
            @Value("${patchlens.sandbox.cpus:1.0}") String cpus,
            @Value("${patchlens.sandbox.memory:1g}") String memory,
            @Value("${patchlens.sandbox.pids-limit:256}") int pidsLimit,
            @Value("${patchlens.sandbox.user:65534:65534}") String user,
            @Value("${patchlens.sandbox.tmpfs-size:512m}") String tmpfsSize,
            @Value("${patchlens.sandbox.max-concurrent:2}") int maxConcurrent,
            @Value("${patchlens.sandbox.max-step-seconds:900}") int maxStepSeconds,
            @Value("${patchlens.sandbox.prefetch-timeout-seconds:600}") int prefetchTimeoutSeconds,
            @Value("${patchlens.sandbox.max-log-chars:10000}") int maxLogChars) {
        this.enabled = enabled;
        this.dockerBinary = dockerBinary;
        this.image = image;
        this.workRoot = Path.of(workRoot);
        this.cacheRoot = Path.of(cacheRoot);
        this.cpus = cpus;
        this.memory = memory;
        this.pidsLimit = pidsLimit;
        this.user = user;
        this.tmpfsSize = tmpfsSize;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxStepSeconds = maxStepSeconds;
        this.prefetchTimeoutSeconds = prefetchTimeoutSeconds;
        this.maxLogChars = maxLogChars;
    }

    /** Defaults suitable for tests; work and cache roots under the given directory. */
    public static SandboxProperties forTests(Path root, boolean enabled) {
        return new SandboxProperties(enabled, "docker", "maven:3.9.9-eclipse-temurin-21",
                root.resolve("work").toString(), root.resolve("cache").toString(),
                "1.0", "1g", 256, "65534:65534", "512m", 2, 900, 600, 10_000);
    }

    public boolean isEnabled()             { return enabled; }
    public String getDockerBinary()        { return dockerBinary; }
    public String getImage()               { return image; }
    public Path getWorkRoot()              { return workRoot; }
    public Path getCacheRoot()             { return cacheRoot; }
    public String getCpus()                { return cpus; }
    public String getMemory()              { return memory; }
    public int getPidsLimit()              { return pidsLimit; }
    public String getUser()                { return user; }
    public String getTmpfsSize()           { return tmpfsSize; }
    public int getMaxConcurrent()          { return maxConcurrent; }
    public int getMaxStepSeconds()         { return maxStepSeconds; }
    public int getPrefetchTimeoutSeconds() { return prefetchTimeoutSeconds; }
    public int getMaxLogChars()            { return maxLogChars; }
}
