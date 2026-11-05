package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.FindingCategory;
import com.patchlens.model.FindingSeverity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.patchlens.service.FindingFilterService.Rejection.*;
import static org.assertj.core.api.Assertions.assertThat;

class FindingFilterServiceTest {

    private final FindingFilterService filter = new FindingFilterService();

    private static final String A = "src/main/java/A.java";
    /** Hunks cover new-side lines 10–14 and 40–41. */
    private static final ChangedFile FILE_A = new ChangedFile(A, "modified", 3, 1, 4,
            "@@ -10,4 +10,5 @@ class A {\n a\n-b\n+c\n+d\n e\n f\n@@ -38,2 +40,2 @@\n x\n-y\n+z");
    private static final ChangedFile REMOVED = new ChangedFile("src/main/java/Gone.java", "removed", 0, 5, 5, "@@ -1,5 +0,0 @@\n-a");
    private static final ChangedFile BINARY = new ChangedFile("logo.png", "added", 0, 0, 0, null);
    private static final List<ChangedFile> PR = List.of(FILE_A, REMOVED, BINARY);

    private static OpenAIService.DetectedFinding f(String file, int s, int e, FindingCategory c, double conf, String title,
                                                  List<OpenAIService.Evidence> evidence) {
        return new OpenAIService.DetectedFinding(file, s, e, c, FindingSeverity.HIGH, title, "explanation", evidence, conf);
    }

    private static OpenAIService.DetectedFinding f(String file, int s, int e, FindingCategory c, double conf, String title) {
        return f(file, s, e, c, conf, title, List.of());
    }

    @Test
    void acceptsFindingOnChangedLines() {
        var report = filter.filterWithReport(List.of(f(A, 11, 12, FindingCategory.CORRECTNESS, 0.9, "ok")), PR);
        assertThat(report.accepted()).hasSize(1);
        assertThat(report.rejected()).isEmpty();
    }

    @Test
    void rejectsHallucinatedFile() {
        var report = filter.filterWithReport(List.of(f("src/main/java/Invented.java", 11, 12, FindingCategory.SECURITY, 0.9, "x")), PR);
        assertThat(report.accepted()).isEmpty();
        assertThat(report.count(FILE_NOT_IN_PR)).isEqualTo(1);
    }

    @Test
    void rejectsLinesThatAreNotInTheDiff() {
        var report = filter.filterWithReport(List.of(
                f(A, 20, 30, FindingCategory.SECURITY, 0.9, "between hunks"),
                f(A, 500, 510, FindingCategory.SECURITY, 0.9, "impossible line")), PR);
        assertThat(report.accepted()).isEmpty();
        assertThat(report.count(LINES_OUTSIDE_DIFF)).isEqualTo(2);
    }

    @Test
    void rejectsInvalidRangesDeletedFilesAndFilesWithoutDiff() {
        var report = filter.filterWithReport(List.of(
                f(A, 0, 5, FindingCategory.SECURITY, 0.9, "zero start"),
                f(A, 14, 12, FindingCategory.SECURITY, 0.9, "inverted"),
                f("src/main/java/Gone.java", 1, 2, FindingCategory.SECURITY, 0.9, "deleted"),
                f("logo.png", 1, 1, FindingCategory.SECURITY, 0.9, "binary")), PR);
        assertThat(report.accepted()).isEmpty();
        assertThat(report.count(INVALID_LINE_RANGE)).isEqualTo(2);
        assertThat(report.count(FILE_DELETED)).isEqualTo(1);
        assertThat(report.count(NO_DIFF_FOR_FILE)).isEqualTo(1);
    }

    @Test
    void rejectsMissingFieldsAndLowConfidence() {
        var missing = new OpenAIService.DetectedFinding(A, 11, 11, null, FindingSeverity.HIGH, "t", "e", List.of(), 0.9);
        var report = filter.filterWithReport(List.of(missing, f(A, 11, 11, FindingCategory.SECURITY, 0.1, "weak")), PR);
        assertThat(report.count(MISSING_FIELDS)).isEqualTo(1);
        assertThat(report.count(LOW_CONFIDENCE)).isEqualTo(1);
    }

    @Test
    void stripsEvidenceOutsideTheDiffButKeepsGroundedEvidence() {
        var finding = f(A, 11, 12, FindingCategory.SECURITY, 0.9, "x", List.of(
                new OpenAIService.Evidence(A, 12, "real"),
                new OpenAIService.Evidence(A, 300, "invented line"),
                new OpenAIService.Evidence("src/Other.java", 1, "invented file")));
        var report = filter.filterWithReport(List.of(finding), PR);
        assertThat(report.accepted().get(0).evidence()).extracting(OpenAIService.Evidence::line).containsExactly(12);
        assertThat(report.evidenceStripped()).isEqualTo(2);
    }

    @Test
    void duplicatesKeepHighestConfidenceRegardlessOfInputOrder() {
        var low  = f(A, 10, 12, FindingCategory.SECURITY, 0.5, "low");
        var high = f(A, 11, 13, FindingCategory.SECURITY, 0.9, "high");
        var otherCategory = f(A, 11, 13, FindingCategory.CORRECTNESS, 0.6, "different issue, same lines");

        var a = filter.filterWithReport(List.of(low, high, otherCategory), PR);
        var b = filter.filterWithReport(List.of(otherCategory, high, low), PR);

        assertThat(a.accepted()).extracting(OpenAIService.DetectedFinding::title).containsExactlyInAnyOrder("high", "different issue, same lines");
        assertThat(a.accepted()).isEqualTo(b.accepted());
        assertThat(a.count(DUPLICATE)).isEqualTo(1);
    }

    @Test
    void capsFindingsPerPrKeepingTheMostConfident() {
        List<OpenAIService.DetectedFinding> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(f(A, 10 + (i % 5), 10 + (i % 5), FindingCategory.values()[i % FindingCategory.values().length], 0.4 + i * 0.01, "f" + i));
        }
        var report = filter.filterWithReport(many, PR);
        assertThat(report.accepted().size()).isLessThanOrEqualTo(FindingFilterService.MAX_FINDINGS_PER_PR);
        assertThat(report.accepted()).extracting(OpenAIService.DetectedFinding::title).doesNotContain("f0");
    }
}
