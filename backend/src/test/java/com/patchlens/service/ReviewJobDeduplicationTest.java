package com.patchlens.service;

import com.patchlens.model.ReviewJob;
import com.patchlens.repository.ReviewJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that duplicate webhook events for the same PR commit are deduplicated
 * into exactly one ReviewJob, both under sequential and concurrent load.
 *
 * <p>Uses a minimal Spring context (H2 in-memory + JPA) so no external
 * services (PostgreSQL, RabbitMQ, Redis) are required.
 * Hibernate DDL is disabled; the review_jobs table is created by
 * review_jobs_h2.sql which includes the unique constraint on
 * (repository_owner, repository_name, pull_request_number, head_sha).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ReviewJobDeduplicationTest.TestConfig.class)
@Sql("/review_jobs_h2.sql")
class ReviewJobDeduplicationTest {

    // ── shared fixtures ──────────────────────────────────────────────────────

    private static final String OWNER    = "acme";
    private static final String REPO     = "backend";
    private static final int    PR_NUM   = 42;
    private static final String PR_URL   = "https://github.com/acme/backend/pull/42";
    private static final String HEAD_SHA = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    @Autowired ReviewJobRepository repository;
    @Autowired ReviewJobService    service;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    // ── sequential ───────────────────────────────────────────────────────────

    /**
     * 50 sequential calls for the same (owner, repo, PR, head SHA) must create
     * exactly 1 job.  Calls 2-50 find the existing PENDING job and return it.
     */
    @Test
    void sequential_50_duplicateWebhooks_createsSingleJob() {
        for (int i = 0; i < 50; i++) {
            ReviewJob job = service.createOrFind(OWNER, REPO, PR_NUM, PR_URL, HEAD_SHA);
            assertThat(job).isNotNull();
        }
        assertThat(repository.count()).isEqualTo(1);
    }

    // ── concurrent ───────────────────────────────────────────────────────────

    /**
     * 50 concurrent threads released simultaneously (CountDownLatch) for the
     * same (owner, repo, PR, head SHA) must create exactly 1 job.
     *
     * <p>The unique DB constraint ensures only one INSERT wins.  The loser
     * threads catch DataIntegrityViolationException inside
     * {@code createOrFind()} and re-query, so all 50 calls complete without
     * throwing to the caller and all receive the same job ID.
     *
     * <p>{@code NOT_SUPPORTED} disables the outer test transaction so spawned
     * threads can each commit their own independent transaction.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrent_50_duplicateWebhooks_createsSingleJob() throws InterruptedException {
        int n = 50;
        CountDownLatch ready   = new CountDownLatch(n);
        CountDownLatch start   = new CountDownLatch(1);
        CountDownLatch done    = new CountDownLatch(n);
        AtomicInteger  errors  = new AtomicInteger(0);
        List<ReviewJob> returned = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ReviewJob job = service.createOrFind(OWNER, REPO, PR_NUM, PR_URL, HEAD_SHA);
                    synchronized (returned) { returned.add(job); }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();              // release all 50 simultaneously
        boolean completed = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completed).as("all threads finish within 10 s").isTrue();
        assertThat(errors.get()).as("no exceptions propagate to callers").isEqualTo(0);
        assertThat(returned).as("all 50 calls return a job").hasSize(n);

        long distinctIds = returned.stream().map(ReviewJob::getId).distinct().count();
        assertThat(distinctIds).as("all callers receive the same job").isEqualTo(1);

        assertThat(repository.count()).as("exactly 1 row in DB").isEqualTo(1);
    }

    // ── minimal Spring context ───────────────────────────────────────────────

    @Configuration
    @EnableJpaRepositories(basePackages = "com.patchlens.repository")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            // H2 in-memory DB; schema created by @Sql("/review_jobs_h2.sql")
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .build();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
            adapter.setDatabase(Database.H2);
            adapter.setGenerateDdl(false);  // DDL is handled by review_jobs_h2.sql
            emf.setJpaVendorAdapter(adapter);
            emf.setPackagesToScan("com.patchlens.model");
            Properties props = new Properties();
            props.setProperty("hibernate.hbm2ddl.auto", "none");  // no DDL auto-generation
            emf.setJpaProperties(props);
            return emf;
        }

        @Bean
        PlatformTransactionManager transactionManager(
                jakarta.persistence.EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean
        JobStatusEmitter jobStatusEmitter() {
            return mock(JobStatusEmitter.class);
        }

        @Bean
        ReviewJobService reviewJobService(ReviewJobRepository repo, JobStatusEmitter emitter) {
            return new ReviewJobService(repo, emitter);
        }
    }
}
