package com.patchlens.service;

import com.patchlens.model.CommentStatus;
import com.patchlens.model.JobStatus;
import com.patchlens.model.ReviewJob;
import com.patchlens.repository.ReviewJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.springframework.web.client.ResourceAccessException;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Job ownership, outbox recovery and comment delivery against a real database (H2) with real
 * transactions and row locks. RabbitMQ and GitHub are replaced by controllable fakes so that
 * broker outages, crashes and ambiguous network outcomes can be reproduced deterministically.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JobReliabilityTest.TestConfig.class)
@Sql("/review_jobs_h2.sql")
class JobReliabilityTest {

    private static final String SHA = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    @Autowired ReviewJobRepository repository;
    @Autowired ReviewJobService jobs;
    @Autowired JobPublisher publisher;
    @Autowired RabbitTemplate rabbit;
    @Autowired FakeGitHub github;
    @Autowired CommentDeliveryService delivery;
    @Autowired ReliabilityScheduler scheduler;
    @Autowired MutableClock clock;

    private ReviewJob job;

    @BeforeEach
    void setUp() {
        clock.now = Instant.parse("2025-06-01T12:00:00Z");
        jobs.setClock(clock);
        scheduler.setClock(clock);
        reset(rabbit);
        github.reset();
        job = jobs.createOrFind("acme", "backend", 42, "https://github.com/acme/backend/pull/42", SHA);
    }

    @AfterEach
    void cleanup() { repository.deleteAll(); }

    // ── worker ownership ─────────────────────────────────────────────────────

    @Test
    void concurrentWorkers_exactlyOneClaimsTheJob() throws Exception {
        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<JobLease>>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> { start.await(); return jobs.claim(job.getId()); }));
        }
        start.countDown();
        int claimed = 0;
        for (var f : results) {
            Optional<JobLease> l = f.get(30, TimeUnit.SECONDS);
            if (l.isPresent()) { claimed++; l.get().close(); }
        }
        pool.shutdown();

        assertThat(claimed).isEqualTo(1);
        assertThat(repository.findById(job.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void crashedWorker_leaseExpires_newDeliveryTakesOver_andStaleWorkerIsFenced() {
        JobLease crashed = jobs.claim(job.getId()).orElseThrow();
        crashed.close(); // process died: heartbeat stops, lease is never released

        // Redelivered immediately: the lease is still live, so the duplicate is not processed.
        assertThat(jobs.claim(job.getId())).isEmpty();

        clock.advance(jobs.leaseDuration().plusSeconds(1));
        JobLease takeover = jobs.claim(job.getId()).orElseThrow();

        // The original worker comes back (e.g. after a long GC pause): every write is rejected.
        assertThatThrownBy(() -> jobs.advance(crashed, JobStatus.GENERATING_PATCHES))
                .isInstanceOf(JobLease.LeaseLostException.class);
        assertThatThrownBy(() -> jobs.complete(crashed, "h", "{}", 0, ReviewJobService.CommentPlan.none()))
                .isInstanceOf(JobLease.LeaseLostException.class);
        jobs.fail(crashed, "late failure"); // no-op for a non-owner

        ReviewJob row = repository.findById(job.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(row.getLeaseOwner()).isEqualTo(takeover.token());

        jobs.complete(takeover, "h", "{}", 0, ReviewJobService.CommentPlan.none());
        takeover.close();
        assertThat(repository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void duplicateMessageAfterCompletion_isNotProcessedAgain() {
        JobLease lease = jobs.claim(job.getId()).orElseThrow();
        jobs.complete(lease, "h", "{}", 0, ReviewJobService.CommentPlan.none());
        lease.close();

        assertThat(jobs.claim(job.getId())).isEmpty();
        assertThat(repository.findById(job.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void failedAttemptReleasesTheLeaseSoTheBrokerRetryCanClaim() {
        JobLease first = jobs.claim(job.getId()).orElseThrow();
        jobs.fail(first, "transient");
        first.close();

        JobLease retry = jobs.claim(job.getId()).orElseThrow();
        retry.close();
        assertThat(repository.findById(job.getId()).orElseThrow().getAttemptCount()).isEqualTo(2);
    }

    // ── outbox / job publication ─────────────────────────────────────────────

    @Test
    void brokerDownAtWebhookTime_jobStaysInOutbox_andIsRepublishedOnRecovery() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
        assertThat(publisher.publish(job)).isFalse();
        assertThat(repository.findById(job.getId()).orElseThrow().getPublishedAt()).isNull();

        scheduler.republishLostJobs();  // broker still down
        assertThat(repository.findById(job.getId()).orElseThrow().getPublishedAt()).isNull();

        reset(rabbit); // broker is back (e.g. after a restart)
        assertThat(scheduler.republishLostJobs()).isEqualTo(1);
        verify(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
        assertThat(repository.findById(job.getId()).orElseThrow().getPublishedAt()).isEqualTo(clock.instant());

        // Recently published: not republished on the next pass.
        assertThat(scheduler.republishLostJobs()).isZero();
    }

    @Test
    void crashedInProgressJob_isRepublishedAfterLeaseExpiry_butLiveLeasesAreLeftAlone() {
        publisher.publish(job);
        JobLease crashed = jobs.claim(job.getId()).orElseThrow();
        crashed.close();

        clock.advance(Duration.ofSeconds(30));
        assertThat(scheduler.republishLostJobs()).as("lease still live").isZero();

        clock.advance(jobs.leaseDuration());
        assertThat(scheduler.republishLostJobs()).as("lease expired").isEqualTo(1);
        assertThat(jobs.claim(job.getId())).isPresent();
    }

    // ── comment delivery ─────────────────────────────────────────────────────

    private void completeWithComment() {
        JobLease lease = jobs.claim(job.getId()).orElseThrow();
        jobs.complete(lease, "h", "{}", 1,
                ReviewJobService.CommentPlan.deliver(ReviewCommentService.markerFor(job.getId()) + "\nbody"));
        lease.close();
    }

    @Test
    void postTimesOutAfterGitHubCreatedTheComment_retryUpdatesInsteadOfDuplicating() {
        completeWithComment();
        github.failNextCreateAfterSuccess = true; // GitHub accepts the POST, client times out

        assertThat(delivery.deliver(job.getId())).contains(CommentStatus.FAILED_RETRYABLE);
        ReviewJob afterFailure = repository.findById(job.getId()).orElseThrow();
        assertThat(afterFailure.getStatus()).as("analysis is not re-run").isEqualTo(JobStatus.COMPLETED);
        assertThat(afterFailure.getCommentNextAttemptAt()).isAfter(clock.instant());

        assertThat(scheduler.retryDueComments()).as("backoff respected").isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(scheduler.retryDueComments()).isEqualTo(1);

        ReviewJob delivered = repository.findById(job.getId()).orElseThrow();
        assertThat(delivered.getCommentStatus()).isEqualTo(CommentStatus.DELIVERED);
        assertThat(github.comments).hasSize(1);
        assertThat(github.creates.get()).isEqualTo(1);
        assertThat(github.updates.get()).isEqualTo(1);
        assertThat(delivered.getCommentId()).isEqualTo(github.comments.keySet().iterator().next());
    }

    @Test
    void permanentGitHubErrorStopsRetrying() {
        completeWithComment();
        github.nextError = new com.patchlens.exception.GitHubApiException("GITHUB_API_ERROR", "Unprocessable", 422);

        assertThat(delivery.deliver(job.getId())).contains(CommentStatus.FAILED_PERMANENT);
        clock.advance(Duration.ofHours(2));
        assertThat(scheduler.retryDueComments()).isZero();
    }

    @Test
    void retryBudgetIsBounded() {
        completeWithComment();
        github.alwaysFail = true;
        CommentStatus last = null;
        for (int i = 0; i < 20; i++) {
            clock.advance(Duration.ofHours(1));
            Optional<CommentStatus> s = delivery.deliver(job.getId());
            if (s.isEmpty()) break;
            last = s.get();
        }
        assertThat(last).isEqualTo(CommentStatus.FAILED_PERMANENT);
        assertThat(repository.findById(job.getId()).orElseThrow().getCommentAttempts())
                .isEqualTo(ReviewJobService.MAX_COMMENT_ATTEMPTS);
    }

    @Test
    void deliveryAbandonedByACrashIsResumedAfterRestart() {
        completeWithComment();
        jobs.claimCommentDelivery(job.getId()).orElseThrow(); // process dies while DELIVERING

        assertThat(scheduler.retryDueComments()).as("in-flight lease still live").isZero();
        clock.advance(ReviewJobService.COMMENT_LEASE.plusSeconds(1));
        assertThat(scheduler.retryDueComments()).isEqualTo(1);
        assertThat(repository.findById(job.getId()).orElseThrow().getCommentStatus()).isEqualTo(CommentStatus.DELIVERED);
        assertThat(github.comments).hasSize(1);
    }

    @Test
    void concurrentDeliveryAttemptsPostOnce() throws Exception {
        completeWithComment();
        github.createDelayMs = 300;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) fs.add(pool.submit(() -> delivery.deliver(job.getId())));
        for (var f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(github.creates.get()).isEqualTo(1);
        assertThat(github.comments).hasSize(1);
    }

    @Test
    void staleCommitIsNotCommentedOn() {
        completeWithComment();
        github.head = "ffffffffffffffffffffffffffffffffffffffff";
        assertThat(delivery.deliver(job.getId())).contains(CommentStatus.SKIPPED_STALE);
        assertThat(github.comments).isEmpty();
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2025-06-01T12:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** In-memory GitHub issue comments with injectable failures. */
    static final class FakeGitHub extends GitHubService {
        final Map<Long, String> comments = new ConcurrentHashMap<>();
        final AtomicInteger creates = new AtomicInteger(), updates = new AtomicInteger();
        volatile boolean failNextCreateAfterSuccess, alwaysFail;
        volatile RuntimeException nextError;
        volatile long createDelayMs;
        volatile String head = SHA;
        private final AtomicInteger ids = new AtomicInteger(1000);

        FakeGitHub() { super(null); }

        void reset() {
            comments.clear(); creates.set(0); updates.set(0);
            failNextCreateAfterSuccess = false; alwaysFail = false; nextError = null; createDelayMs = 0; head = SHA;
        }

        @Override public Optional<String> fetchHeadSha(String o, String r, int n) { return Optional.of(head); }

        @Override public List<IssueComment> listPrComments(String o, String r, int n) {
            if (alwaysFail) throw new com.patchlens.exception.GitHubApiException("GITHUB_API_ERROR", "Bad Gateway", 502);
            return comments.entrySet().stream().map(e -> new IssueComment(e.getKey(), e.getValue())).toList();
        }

        @Override public long createPrComment(String o, String r, int n, String body) {
            if (nextError != null) { RuntimeException e = nextError; nextError = null; throw e; }
            if (createDelayMs > 0) { try { Thread.sleep(createDelayMs); } catch (InterruptedException ignored) {} }
            long id = ids.incrementAndGet();
            comments.put(id, body);
            creates.incrementAndGet();
            if (failNextCreateAfterSuccess) {
                failNextCreateAfterSuccess = false;
                throw new ResourceAccessException("Read timed out");
            }
            return id;
        }

        @Override public void updatePrComment(String o, String r, long id, String body) {
            comments.put(id, body);
            updates.incrementAndGet();
        }
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.patchlens.repository")
    @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("reliability").build();
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(ds);
            HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
            adapter.setDatabase(Database.H2);
            adapter.setGenerateDdl(false);
            emf.setJpaVendorAdapter(adapter);
            emf.setPackagesToScan("com.patchlens.model");
            Properties p = new Properties();
            p.setProperty("hibernate.hbm2ddl.auto", "none");
            emf.setJpaProperties(p);
            return emf;
        }
        @Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf) { return new JpaTransactionManager(emf); }
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean JobStatusEmitter jobStatusEmitter() { return mock(JobStatusEmitter.class); }
        @Bean ReviewJobService reviewJobService(ReviewJobRepository repo, JobStatusEmitter emitter) { return new ReviewJobService(repo, emitter, 60); }
        @Bean RabbitTemplate rabbitTemplate() { return mock(RabbitTemplate.class); }
        @Bean JobPublisher jobPublisher(RabbitTemplate t, ReviewJobService s) { return new JobPublisher(t, s); }
        @Bean FakeGitHub fakeGitHub() { return new FakeGitHub(); }
        @Bean ReviewCommentService reviewCommentService(FakeGitHub gh) { return new ReviewCommentService(gh, "token"); }
        @Bean CommentDeliveryService commentDeliveryService(ReviewJobService s, ReviewCommentService c) { return new CommentDeliveryService(s, c); }
        @Bean ReliabilityScheduler reliabilityScheduler(ReviewJobRepository repo, ReviewJobService s, JobPublisher p, CommentDeliveryService d) {
            return new ReliabilityScheduler(repo, s, p, d, 600);
        }
    }
}
