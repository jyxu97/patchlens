package com.patchlens.service;

import com.patchlens.model.EvaluationRun;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvaluationMetricsTest {

    private static EvaluationService.Totals totals(int tp, int fp, int fn, int targets, int initialPass, int finalPass) {
        EvaluationService.Totals t = new EvaluationService.Totals();
        t.tp = tp; t.fp = fp; t.fn = fn; t.expected = tp + fn; t.detected = tp + fp;
        t.patchTargets = targets; t.attempted = targets; t.initialPass = initialPass; t.finalPass = finalPass;
        return t;
    }

    @Test
    void precisionRecallAndPercentagePointImprovement() {
        EvaluationRun run = new EvaluationRun();
        EvaluationService.applyMetrics(run, totals(21, 7, 4, 20, 9, 12), true);

        assertThat(run.getRecallScore()).isCloseTo(21.0 / 25, within(1e-9));
        assertThat(run.getPrecisionScore()).isCloseTo(21.0 / 28, within(1e-9));
        assertThat(run.getInitialPatchPassRate()).isCloseTo(0.45, within(1e-9));
        assertThat(run.getFinalPatchPassRate()).isCloseTo(0.60, within(1e-9));
        // percentage points, not relative change (which would be +33%)
        assertThat(run.getPatchPassImprovementPp()).isEqualTo(15.0);
    }

    @Test
    void bothRatesShareTheFixedPatchTargetDenominator() {
        EvaluationRun run = new EvaluationRun();
        EvaluationService.Totals t = totals(1, 0, 4, 5, 1, 2);
        t.attempted = 2; // only 2 targets were detected and attempted…
        EvaluationService.applyMetrics(run, t, true);
        // …but rates are still over all 5 labeled targets
        assertThat(run.getInitialPatchPassRate()).isEqualTo(0.2);
        assertThat(run.getFinalPatchPassRate()).isEqualTo(0.4);
        assertThat(run.getPatchAttemptedCount()).isEqualTo(2);
    }

    @Test
    void patchRatesAreNullWhenSandboxDidNotRun() {
        EvaluationRun run = new EvaluationRun();
        EvaluationService.applyMetrics(run, totals(3, 1, 2, 5, 0, 0), false);
        assertThat(run.getInitialPatchPassRate()).isNull();
        assertThat(run.getFinalPatchPassRate()).isNull();
        assertThat(run.getPatchPassImprovementPp()).isNull();
        assertThat(run.getRecallScore()).isEqualTo(0.6);
    }

    @Test
    void precisionIsNullWithoutDetections() {
        EvaluationRun run = new EvaluationRun();
        EvaluationService.applyMetrics(run, totals(0, 0, 5, 5, 0, 0), true);
        assertThat(run.getPrecisionScore()).isNull();
        assertThat(run.getRecallScore()).isEqualTo(0.0);
    }
}
