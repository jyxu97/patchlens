package com.patchlens.eval;

import com.patchlens.model.FindingCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.patchlens.model.FindingCategory.*;
import static org.assertj.core.api.Assertions.assertThat;

class EvalMatcherTest {

    private static final String F = "src/main/java/A.java";

    private static EvalMatcher.Expected exp(String id, FindingCategory c, int s, int e) { return new EvalMatcher.Expected(id, c, F, s, e); }
    private static EvalMatcher.Detected det(int i, FindingCategory c, String file, int s, int e) { return new EvalMatcher.Detected(i, c, file, s, e); }

    @Test
    void multipleDetectionsOfOneLabeledIssueCountOnceAsTruePositive() {
        var r = EvalMatcher.match(
                List.of(exp("e1", SECURITY, 10, 12)),
                List.of(det(0, SECURITY, F, 10, 10), det(1, SECURITY, F, 11, 12), det(2, SECURITY, F, 12, 12)));

        assertThat(r.truePositives()).isEqualTo(1);
        assertThat(r.falsePositives()).isEqualTo(2);
        assertThat(r.falseNegatives()).isZero();
        assertThat(r.matches()).containsExactly(new EvalMatcher.Match("e1", 0));
    }

    @Test
    void hallucinatedFileOrImpossibleLinesNeverMatch() {
        var r = EvalMatcher.match(
                List.of(exp("e1", SECURITY, 10, 12)),
                List.of(det(0, SECURITY, "src/main/java/Invented.java", 10, 12),
                        det(1, SECURITY, F, 900, 950)));
        assertThat(r.truePositives()).isZero();
        assertThat(r.falsePositives()).isEqualTo(2);
        assertThat(r.falseNegatives()).isEqualTo(1);
    }

    @Test
    void categoryMustMatch() {
        assertThat(EvalMatcher.compatible(exp("e", SECURITY, 10, 10), det(0, CORRECTNESS, F, 10, 10))).isFalse();
    }

    @Test
    void lineToleranceIsBoundedAndPathPrefixesAreNormalised() {
        assertThat(EvalMatcher.compatible(exp("e", SECURITY, 10, 10), det(0, SECURITY, "./" + F, 12, 12))).isTrue();
        assertThat(EvalMatcher.compatible(exp("e", SECURITY, 10, 10), det(0, SECURITY, "b/" + F, 13, 14))).isFalse();
    }

    @Test
    void choosesMaximumAssignmentNotGreedy() {
        // Greedy (e1 takes d0) would leave e2 unmatched; the maximum matching is e1→d1, e2→d0.
        var r = EvalMatcher.match(
                List.of(exp("e1", SECURITY, 10, 20), exp("e2", SECURITY, 30, 30)),
                List.of(det(0, SECURITY, F, 19, 31), det(1, SECURITY, F, 10, 11)));
        assertThat(r.truePositives()).isEqualTo(2);
        assertThat(r.matches()).containsExactly(new EvalMatcher.Match("e1", 1), new EvalMatcher.Match("e2", 0));
    }

    @Test
    void isDeterministicRegardlessOfInputOrder() {
        var a = EvalMatcher.match(List.of(exp("e1", SECURITY, 10, 10), exp("e2", SECURITY, 10, 10)),
                List.of(det(0, SECURITY, F, 10, 10), det(1, SECURITY, F, 10, 10)));
        var b = EvalMatcher.match(List.of(exp("e2", SECURITY, 10, 10), exp("e1", SECURITY, 10, 10)),
                List.of(det(1, SECURITY, F, 10, 10), det(0, SECURITY, F, 10, 10)));
        assertThat(a).isEqualTo(b);
    }
}
