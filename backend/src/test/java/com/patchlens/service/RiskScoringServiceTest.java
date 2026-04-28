package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.RiskScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScoringServiceTest {

    private RiskScoringService service;

    @BeforeEach
    void setUp() {
        service = new RiskScoringService();
    }

    // --- individual scoring rules ---

    @Test
    void authPathShouldScorePlus3() {
        var file = file("src/main/AuthService.java", 10, 5);
        var result = scoreOne(file);

        assertThat(result.score()).isGreaterThanOrEqualTo(3);
        assertThat(result.reasons()).anyMatch(r -> r.contains("authentication"));
    }

    @Test
    void paymentPathShouldScorePlus3() {
        var file = file("src/main/PaymentService.java", 10, 5);
        var result = scoreOne(file);

        assertThat(result.score()).isGreaterThanOrEqualTo(3);
        assertThat(result.reasons()).anyMatch(r -> r.contains("payment"));
    }

    @Test
    void migrationFileShouldScorePlus2() {
        var file = file("db/migration/V1__add_users.sql", 20, 0);
        var result = scoreOne(file);

        assertThat(result.score()).isGreaterThanOrEqualTo(2);
        assertThat(result.reasons()).anyMatch(r -> r.contains("migration"));
    }

    @Test
    void configFileShouldScorePlus2() {
        var file = file("src/main/resources/application.properties", 5, 0);
        var result = scoreOne(file);

        assertThat(result.score()).isGreaterThanOrEqualTo(2);
        assertThat(result.reasons()).anyMatch(r -> r.contains("configuration"));
    }

    @Test
    void largeChangeShouldScorePlus2() {
        // 301 lines total (additions + deletions) exceeds the 300 threshold
        var file = file("src/main/Foo.java", 200, 101);
        var result = scoreOne(file);

        assertThat(result.score()).isGreaterThanOrEqualTo(2);
        assertThat(result.reasons()).anyMatch(r -> r.contains("301 lines"));
    }

    @Test
    void cachePathShouldScorePlus1() {
        var file = file("src/main/RedisCache.java", 10, 5);
        var result = scoreOne(file);

        assertThat(result.score()).isGreaterThanOrEqualTo(1);
        assertThat(result.reasons()).anyMatch(r -> r.contains("caching"));
    }

    // --- PR-level rule: no test files ---

    @Test
    void noTestFilesShouldAddOnePenaltyToEveryFile() {
        var file = file("src/main/Foo.java", 5, 0);
        // Only one file, no test file present → no test files rule fires
        var results = service.score(List.of(file));

        assertThat(results.get(0).reasons()).anyMatch(r -> r.contains("no test files"));
    }

    @Test
    void presenceOfTestFileShouldSuppressNoTestPenalty() {
        var source = file("src/main/Foo.java", 5, 0);
        var test   = file("src/test/FooTest.java", 10, 0);
        var results = service.score(List.of(source, test));

        // Neither file should carry the "no test files" penalty
        results.forEach(r ->
                assertThat(r.reasons()).noneMatch(reason -> reason.contains("no test files"))
        );
    }

    // --- result ordering and overall risk ---

    @Test
    void resultsShouldBeSortedByScoreDescending() {
        var highRisk = file("src/main/AuthService.java", 10, 5);   // auth → +3
        var lowRisk  = file("src/main/README.md", 1, 0);           // no signals
        var results = service.score(List.of(lowRisk, highRisk));

        assertThat(results.get(0).filename()).isEqualTo("src/main/AuthService.java");
    }

    @Test
    void overallRiskShouldBeHighestAmongFiles() {
        var scores = List.of(
                new RiskScore("a.java", 1, RiskScore.RiskLevel.low, List.of()),
                new RiskScore("b.java", 7, RiskScore.RiskLevel.high, List.of()),
                new RiskScore("c.java", 4, RiskScore.RiskLevel.medium, List.of())
        );

        assertThat(service.overallRisk(scores)).isEqualTo(RiskScore.RiskLevel.high);
    }

    @Test
    void overallRiskShouldBeLowWhenNoFiles() {
        assertThat(service.overallRisk(List.of())).isEqualTo(RiskScore.RiskLevel.low);
    }

    // --- helper ---

    /** Creates a ChangedFile with only the fields relevant to risk scoring. */
    private ChangedFile file(String filename, int additions, int deletions) {
        return new ChangedFile(filename, "modified", additions, deletions,
                additions + deletions, null);
    }

    /** Scores a single file in isolation (no other files in the PR). */
    private RiskScore scoreOne(ChangedFile file) {
        return service.score(List.of(file)).get(0);
    }
}