package com.patchlens.service;

import com.patchlens.model.RiskScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eval tests: verifies that the risk scorer correctly flags known-risky files
 * in the built-in sample PR fixtures. Each fixture represents a realistic
 * high-risk PR scenario, so at least the most sensitive files should be HIGH.
 */
class RiskyFileRecallTest {

    private SamplePrLoader loader;
    private RiskScoringService scorer;

    @BeforeEach
    void setUp() {
        loader = new SamplePrLoader(new ObjectMapper());
        scorer = new RiskScoringService();
    }

    // --- db-migration sample ---

    @Test
    void dbMigrationSampleShouldFlagMigrationSqlAsHigh() {
        var sample = loader.load("db-migration");
        var scores = scorer.score(sample.files());

        assertThat(scores)
                .filteredOn(s -> s.filename().contains("migration"))
                .isNotEmpty()
                .allMatch(s -> s.riskLevel() == RiskScore.RiskLevel.high);
    }

    @Test
    void dbMigrationSampleShouldFlagAuthControllerAsAtLeastMedium() {
        var sample = loader.load("db-migration");
        var scores = scorer.score(sample.files());

        assertThat(scores)
                .filteredOn(s -> s.filename().contains("Auth"))
                .isNotEmpty()
                .allMatch(s -> s.riskLevel() != RiskScore.RiskLevel.low);
    }

    @Test
    void dbMigrationSampleShouldHaveHighOverallRisk() {
        var sample = loader.load("db-migration");
        var scores = scorer.score(sample.files());

        assertThat(scorer.overallRisk(scores)).isEqualTo(RiskScore.RiskLevel.high);
    }

    // --- stripe-checkout sample ---

    @Test
    void stripeCheckoutSampleShouldFlagPaymentAndBillingFilesAsAtLeastMedium() {
        var sample = loader.load("stripe-checkout");
        var scores = scorer.score(sample.files());

        // billing and payment files should never be scored LOW
        assertThat(scores)
                .filteredOn(s -> s.filename().contains("billing") || s.filename().contains("payment"))
                .isNotEmpty()
                .allMatch(s -> s.riskLevel() != RiskScore.RiskLevel.low);
    }

    @Test
    void stripeCheckoutConfigFileShouldBeHigh() {
        var sample = loader.load("stripe-checkout");
        var scores = scorer.score(sample.files());

        // config + payment keywords combined push this file to HIGH
        assertThat(scores)
                .filteredOn(s -> s.filename().contains("stripe-payment.yml"))
                .isNotEmpty()
                .allMatch(s -> s.riskLevel() == RiskScore.RiskLevel.high);
    }

    @Test
    void stripeCheckoutSampleShouldHaveHighOverallRisk() {
        var sample = loader.load("stripe-checkout");
        var scores = scorer.score(sample.files());

        assertThat(scorer.overallRisk(scores)).isEqualTo(RiskScore.RiskLevel.high);
    }
}
