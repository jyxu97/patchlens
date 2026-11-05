package com.patchlens.repository;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Versioned pgvector retrieval against a real PostgreSQL + pgvector instance (the docker-compose
 * {@code postgres} service on localhost:5432). Skipped — not faked — when it is not reachable.
 *
 * <p>Run: {@code docker compose up -d postgres && ./mvnw test -Dtest=ContextIndexVersioningPgIT}
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContextIndexVersioningPgIT {

    private static final String URL = System.getProperty("pg.url", "jdbc:postgresql://localhost:5432/patchlens");
    private AnnotationConfigApplicationContext ctx;
    private ContextChunkRepository chunks;
    private final String repo = "it-" + UUID.randomUUID();

    @BeforeAll
    void connect() {
        boolean reachable;
        try (var c = DriverManager.getConnection(URL, "patchlens", "patchlens")) {
            c.createStatement().execute("CREATE EXTENSION IF NOT EXISTS vector");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "PostgreSQL not reachable at " + URL + " — ContextIndexVersioningPgIT NOT executed");
        ctx = new AnnotationConfigApplicationContext(PgConfig.class);
        chunks = ctx.getBean(ContextChunkRepository.class);
    }

    @AfterAll
    void close() {
        if (chunks != null) chunks.deleteByRepositoryOwnerAndRepositoryName("it", repo);
        if (ctx != null) ctx.close();
    }

    private static String vector(float first) {
        return IntStream.range(0, 1536).mapToObj(i -> i == 0 ? String.valueOf(first) : "0.001")
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void retrievalIsScopedToOneVersionAndOldVersionsAreCollectedAfterActivation() {
        chunks.insertChunk("it", repo, "OLD.md", 0, "old content", vector(1f), null, "v1");
        chunks.insertChunk("it", repo, "NEW.md", 0, "new content", vector(1f), null, "v2");
        chunks.insertChunk("it", repo, "LEGACY.md", 0, "legacy content", vector(1f), null, null);

        List<Object[]> v1 = chunks.findTopKSimilarWithScoreForVersion("it", repo, "v1", vector(1f), 10);
        List<Object[]> v2 = chunks.findTopKSimilarWithScoreForVersion("it", repo, "v2", vector(1f), 10);
        assertThat(v1).extracting(r -> (String) r[0]).containsExactly("OLD.md");
        assertThat(v2).extracting(r -> (String) r[0]).containsExactly("NEW.md");
        assertThat(chunks.findTopKSimilarWithScore("it", repo, vector(1f), 10)).hasSize(3);

        // after v2 is activated, everything else (older version and untracked chunks) is removed
        assertThat(chunks.deleteAllExceptVersion("it", repo, "v2")).isEqualTo(2);
        assertThat(chunks.findTopKSimilarWithScore("it", repo, vector(1f), 10))
                .extracting(r -> (String) r[0]).containsExactly("NEW.md");

        // a partial rebuild of the same version can be cleared before retrying
        assertThat(chunks.deleteVersion("it", repo, "v2")).isEqualTo(1);
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.patchlens.repository")
    @EnableTransactionManagement
    static class PgConfig {
        @Bean DataSource dataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource(URL, "patchlens", "patchlens");
            ds.setDriverClassName("org.postgresql.Driver");
            return ds;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(ds);
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setPackagesToScan("com.patchlens.model");
            Properties p = new Properties();
            p.setProperty("hibernate.hbm2ddl.auto", "update"); // same schema management as the application
            emf.setJpaProperties(p);
            return emf;
        }
        @Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }
}
