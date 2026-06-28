package com.patchlens.service;

import com.patchlens.dto.GroundingReport;
import com.patchlens.model.ChangedFile;
import com.patchlens.model.ReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingValidationServiceTest {

    private final GroundingValidationService validator = new GroundingValidationService();

    // -- helpers --

    private static ChangedFile file(String filename) {
        return new ChangedFile(filename, "modified", 1, 1, 2, null);
    }

    private static ReviewResult reviewWith(String... riskyPaths) {
        List<ReviewResult.RiskyFile> riskyFiles = java.util.Arrays.stream(riskyPaths)
                .map(p -> new ReviewResult.RiskyFile(p, "medium", "reason"))
                .toList();
        return new ReviewResult(
                new ReviewResult.Summary("title", "overview", List.of()),
                new ReviewResult.RiskAssessment("medium", riskyFiles),
                List.of(),
                List.of()
        );
    }

    // -- tests --

    @Test
    void allPathsGrounded() {
        List<ChangedFile> files = List.of(file("src/Foo.java"), file("src/Bar.java"));
        ReviewResult review = reviewWith("src/Foo.java", "src/Bar.java");

        GroundingReport report = validator.validate(review, files);

        assertThat(report.totalRiskyFiles()).isEqualTo(2);
        assertThat(report.groundedCount()).isEqualTo(2);
        assertThat(report.hallucinatedCount()).isEqualTo(0);
        assertThat(report.hallucinatedPaths()).isEmpty();
        assertThat(report.groundingRate()).isEqualTo(1.0);
    }

    @Test
    void allPathsHallucinated() {
        List<ChangedFile> files = List.of(file("src/Real.java"));
        ReviewResult review = reviewWith("src/Made.java", "src/Up.java");

        GroundingReport report = validator.validate(review, files);

        assertThat(report.totalRiskyFiles()).isEqualTo(2);
        assertThat(report.groundedCount()).isEqualTo(0);
        assertThat(report.hallucinatedCount()).isEqualTo(2);
        assertThat(report.hallucinatedPaths()).containsExactlyInAnyOrder("src/Made.java", "src/Up.java");
        assertThat(report.groundingRate()).isEqualTo(0.0);
    }

    @Test
    void partialGrounding() {
        List<ChangedFile> files = List.of(file("src/Real.java"), file("src/Auth.java"));
        ReviewResult review = reviewWith("src/Real.java", "src/Invented.java");

        GroundingReport report = validator.validate(review, files);

        assertThat(report.totalRiskyFiles()).isEqualTo(2);
        assertThat(report.groundedCount()).isEqualTo(1);
        assertThat(report.hallucinatedCount()).isEqualTo(1);
        assertThat(report.hallucinatedPaths()).containsExactly("src/Invented.java");
        assertThat(report.groundingRate()).isEqualTo(0.5);
    }

    @Test
    void noRiskyFiles_returnsFullGrounding() {
        List<ChangedFile> files = List.of(file("src/Foo.java"));
        ReviewResult review = reviewWith(); // no risky files

        GroundingReport report = validator.validate(review, files);

        assertThat(report.totalRiskyFiles()).isEqualTo(0);
        assertThat(report.hallucinatedCount()).isEqualTo(0);
        assertThat(report.groundingRate()).isEqualTo(1.0);
    }

    @Test
    void noChangedFiles_allHallucinated() {
        List<ChangedFile> files = List.of();
        ReviewResult review = reviewWith("src/Foo.java");

        GroundingReport report = validator.validate(review, files);

        assertThat(report.hallucinatedCount()).isEqualTo(1);
        assertThat(report.groundingRate()).isEqualTo(0.0);
    }

    @Test
    void duplicateRiskyPathsCountedOnce() {
        // AI mentions the same path twice; should count as 2 risky files but 1 unique hallucination
        List<ChangedFile> files = List.of(file("src/Real.java"));
        ReviewResult review = reviewWith("src/Ghost.java", "src/Ghost.java");

        GroundingReport report = validator.validate(review, files);

        // totalRiskyFiles counts raw list entries
        assertThat(report.totalRiskyFiles()).isEqualTo(2);
        // hallucinatedPaths is distinct
        assertThat(report.hallucinatedPaths()).containsExactly("src/Ghost.java");
    }
}
