package com.patchlens.repository;

import com.patchlens.model.ReviewJob;
import com.patchlens.service.JobLease;
import com.patchlens.service.JobStatusEmitter;
import com.patchlens.service.ReviewJobService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Concurrent job claims on real PostgreSQL row locks ({@code SELECT … FOR UPDATE}).
 * Skipped — not faked — when PostgreSQL is not reachable on localhost:5432.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobClaimPgIT {

    private AnnotationConfigApplicationContext ctx;
    private ReviewJobService jobs;
    private ReviewJobRepository repo;

    @BeforeAll
    void connect() {
        boolean reachable;
        try (var c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/patchlens", "patchlens", "patchlens")) {
            reachable = c.isValid(2);
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "PostgreSQL not reachable — JobClaimPgIT NOT executed");
        ctx = new AnnotationConfigApplicationContext(Config.class);
        jobs = ctx.getBean(ReviewJobService.class);
        repo = ctx.getBean(ReviewJobRepository.class);
    }

    @AfterAll
    void close() { if (ctx != null) ctx.close(); }

    @Test
    void exactlyOneOfManyConcurrentDeliveriesClaimsTheJob() throws Exception {
        ReviewJob job = jobs.createOrFind("it-" + UUID.randomUUID(), "repo", 1, "u",
                UUID.randomUUID().toString().replace("-", "").substring(0, 32) + "00000000");
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<JobLease>>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) fs.add(pool.submit(() -> { go.await(); return jobs.claim(job.getId()); }));
        go.countDown();
        int claimed = 0;
        for (var f : fs) {
            var l = f.get(30, TimeUnit.SECONDS);
            if (l.isPresent()) { claimed++; l.get().close(); }
        }
        pool.shutdown();
        assertThat(claimed).isEqualTo(1);
        assertThat(repo.findById(job.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
        repo.deleteById(job.getId());
    }

    @Configuration
    @Import(ContextIndexVersioningPgIT.PgConfig.class)
    static class Config {
        @Bean JobStatusEmitter emitter() { return mock(JobStatusEmitter.class); }
        @Bean ReviewJobService reviewJobService(ReviewJobRepository r, JobStatusEmitter e) { return new ReviewJobService(r, e, 60); }
    }
}
