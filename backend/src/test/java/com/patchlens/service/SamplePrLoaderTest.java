package com.patchlens.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplePrLoaderTest {

    private SamplePrLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SamplePrLoader(new ObjectMapper());
    }

    @Test
    void shouldLoadRedisSamplePr() {
        var sample = loader.load("redis-session-cache");

        assertThat(sample.metadata().owner()).isEqualTo("example");
        assertThat(sample.metadata().repo()).isEqualTo("demo-app");
        assertThat(sample.metadata().pullNumber()).isEqualTo(42);
        assertThat(sample.files()).isNotEmpty();
    }

    @Test
    void shouldLoadDbMigrationSamplePr() {
        var sample = loader.load("db-migration");

        assertThat(sample.metadata().repo()).isEqualTo("user-service");
        assertThat(sample.files()).isNotEmpty();
        assertThat(sample.files()).anyMatch(f -> f.filename().contains("migration"));
    }

    @Test
    void shouldLoadStripeCheckoutSamplePr() {
        var sample = loader.load("stripe-checkout");

        assertThat(sample.metadata().repo()).isEqualTo("saas-platform");
        assertThat(sample.files()).isNotEmpty();
        assertThat(sample.files()).anyMatch(f -> f.filename().contains("billing"));
    }

    @Test
    void allSamplesShouldHaveNonBlankTitleAndAtLeastOneFile() {
        for (String id : List.of("redis-session-cache", "db-migration", "stripe-checkout")) {
            var sample = loader.load(id);
            assertThat(sample.metadata().title()).isNotBlank();
            assertThat(sample.files()).isNotEmpty();
        }
    }

    @Test
    void shouldThrowForUnknownSampleId() {
        assertThatThrownBy(() -> loader.load("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist");
    }
}
