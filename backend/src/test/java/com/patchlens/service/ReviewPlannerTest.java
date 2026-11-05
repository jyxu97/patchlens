package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.ReviewCoverage;
import com.patchlens.model.RiskScore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.patchlens.model.ReviewCoverage.Reason.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewPlannerTest {

    private static ChangedFile file(String name, int patchChars) {
        return new ChangedFile(name, "modified", 1, 1, 2, "@@ -1 +1 @@\n" + "x".repeat(Math.max(0, patchChars - 12)));
    }

    private static RiskScore risk(String name, int score) {
        return new RiskScore(name, score, RiskScore.toLevel(score), List.of());
    }

    @Test
    void largePr_reviewsHighestRiskFilesUpToTheLimit_andRecordsEverySkip() {
        List<ChangedFile> files = new ArrayList<>();
        List<RiskScore> risks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String n = String.format("src/F%02d.java", i);
            files.add(file(n, 500));
            risks.add(risk(n, i % 10));
        }
        files.add(new ChangedFile("logo.png", "added", 0, 0, 0, null));
        files.add(new ChangedFile("src/Old.java", "removed", 0, 10, 10, "@@ -1,10 +0,0 @@\n-x"));

        ReviewPlanner.Plan plan = ReviewPlanner.plan(files, risks, 20, 4000, 32_000, 3);
        ReviewCoverage c = plan.coverage();

        assertThat(c.totalFiles()).isEqualTo(32);
        assertThat(c.reviewedFiles()).hasSize(20);
        assertThat(c.skippedFiles()).hasSize(12);
        assertThat(c.skippedFiles()).filteredOn(s -> s.reason() == FILE_LIMIT).hasSize(10);
        assertThat(c.skippedFiles()).extracting(ReviewCoverage.SkippedFile::reason).contains(NO_TEXT_DIFF, REMOVED);
        // every changed file is accounted for exactly once
        assertThat(c.reviewedFiles().size() + c.skippedFiles().size()).isEqualTo(files.size());
        // risk-ordered: no skipped-for-limit file is riskier than a reviewed one
        int minReviewed = c.reviewedFiles().stream().mapToInt(n -> risks.stream().filter(r -> r.filename().equals(n)).findFirst().orElseThrow().score()).min().orElseThrow();
        c.skippedFiles().stream().filter(s -> s.reason() == FILE_LIMIT)
                .forEach(s -> assertThat(risks.stream().filter(r -> r.filename().equals(s.file())).findFirst().orElseThrow().score()).isLessThanOrEqualTo(minReviewed));
    }

    @Test
    void diffsAreTruncatedAndBatchedWithinTheBudget() {
        List<ChangedFile> files = List.of(file("a.java", 9_000), file("b.java", 3_000), file("c.java", 3_000), file("d.java", 3_000));
        ReviewPlanner.Plan plan = ReviewPlanner.plan(files, List.of(), 20, 4_000, 7_000, 10);

        assertThat(plan.coverage().truncatedFiles()).containsExactly("a.java");
        assertThat(plan.reviewedFiles()).allSatisfy(f -> assertThat(f.patch().length()).isLessThanOrEqualTo(4_000));
        assertThat(plan.batches()).allSatisfy(b ->
                assertThat(b.stream().mapToInt(f -> f.patch().length()).sum()).isLessThanOrEqualTo(7_000));
        assertThat(plan.coverage().batches()).isEqualTo(plan.batches().size()).isEqualTo(2);
    }

    @Test
    void filesBeyondTheBatchLimitAreSkippedNotSilentlyDropped() {
        List<ChangedFile> files = List.of(file("a.java", 3_000), file("b.java", 3_000), file("c.java", 3_000));
        ReviewPlanner.Plan plan = ReviewPlanner.plan(files, List.of(), 20, 4_000, 3_000, 2);

        assertThat(plan.batches()).hasSize(2);
        assertThat(plan.coverage().skippedFiles()).containsExactly(new ReviewCoverage.SkippedFile("c.java", BATCH_LIMIT));
    }

    @Test
    void planIsDeterministic() {
        List<ChangedFile> files = List.of(file("b.java", 100), file("a.java", 100), file("c.java", 100));
        var p1 = ReviewPlanner.plan(files, List.of(), 2, 4_000, 32_000, 3);
        var p2 = ReviewPlanner.plan(List.of(files.get(2), files.get(0), files.get(1)), List.of(), 2, 4_000, 32_000, 3);
        assertThat(p1.coverage().reviewedFiles()).isEqualTo(p2.coverage().reviewedFiles()).containsExactly("a.java", "b.java");
    }
}
