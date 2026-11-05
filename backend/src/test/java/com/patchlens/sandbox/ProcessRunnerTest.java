package com.patchlens.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerTest {

    private static final Map<String, String> ENV = Map.of("PATH", "/usr/bin:/bin");

    /**
     * Regression: the old implementation called readAllBytes() on the output pipe before
     * waitFor(timeout), so a process that never exits blocked forever and bypassed the timeout.
     */
    @Test
    void neverEndingProcessIsKilledAtTheTimeout() throws Exception {
        AtomicBoolean onTimeoutCalled = new AtomicBoolean();
        long start = System.nanoTime();
        ProcessRunner.Result r = ProcessRunner.run(List.of("sh", "-c", "echo started; sleep 60"),
                null, Duration.ofSeconds(1), ENV, 10_000, () -> onTimeoutCalled.set(true));
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toSeconds();

        assertThat(r.timedOut()).isTrue();
        assertThat(onTimeoutCalled).isTrue();
        assertThat(elapsed).isLessThan(15);
        assertThat(r.output()).contains("started").contains("[TIMEOUT after 1s]");
    }

    @Test
    void outputLargerThanPipeBufferDoesNotDeadlockAndIsBounded() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(
                List.of("sh", "-c", "i=0; while [ $i -lt 200000 ]; do echo line-$i; i=$((i+1)); done; echo END"),
                null, Duration.ofSeconds(60), ENV, 2_000, null);

        assertThat(r.timedOut()).isFalse();
        assertThat(r.exitCode()).isZero();
        assertThat(r.output().length()).isLessThan(2_200);
        assertThat(r.output()).endsWith("END\n").contains("chars omitted");
    }

    @Test
    void childReceivesOnlyTheExplicitEnvironment() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(List.of("sh", "-c", "env"),
                null, Duration.ofSeconds(10), Map.of("PATH", "/usr/bin:/bin", "ONLY_THIS", "1"), 10_000, null);

        assertThat(r.output()).contains("ONLY_THIS=1");
        assertThat(r.output()).doesNotContain("HOME=" + System.getProperty("user.home"));
        assertThat(r.output()).doesNotContain("JAVA_HOME");
    }

    @Test
    void exitCodeIsPropagated() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(List.of("sh", "-c", "exit 3"), null, Duration.ofSeconds(10), ENV, 100, null);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.timedOut()).isFalse();
    }
}
