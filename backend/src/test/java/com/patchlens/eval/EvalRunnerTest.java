package com.patchlens.eval;

import com.patchlens.dto.GroundingReport;
import com.patchlens.model.ReviewResult;
import com.patchlens.model.RiskScore;
import com.patchlens.service.GroundingValidationService;
import com.patchlens.service.OpenAIService;
import com.patchlens.service.RiskScoringService;
import com.patchlens.service.SamplePrLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the analysis pipeline against all three built-in sample PRs,
 * asserts structural correctness, and writes a markdown report to
 * target/eval-report.md.
 *
 * No Spring context or external services required — runs in mock AI mode.
 */
class EvalRunnerTest {

    // Sample IDs paired with the minimum expected overall risk level
    private static final Object[][] SAMPLES = {
            {"redis-session-cache", RiskScore.RiskLevel.medium},
            {"db-migration",        RiskScore.RiskLevel.high},
            {"stripe-checkout",     RiskScore.RiskLevel.high},
    };

    private SamplePrLoader loader;
    private RiskScoringService riskScorer;
    private OpenAIService openAI;
    private GroundingValidationService grounder;

    @BeforeEach
    void setup() {
        ObjectMapper mapper = new ObjectMapper();
        loader    = new SamplePrLoader(mapper);
        riskScorer = new RiskScoringService();
        // null RestClient is safe in mock mode — the HTTP call is never made
        openAI    = new OpenAIService(null, mapper, "gpt-4o-mini", "mock", 20, 4000);
        grounder  = new GroundingValidationService();
    }

    // ── internal result carrier ──────────────────────────────────────────────

    record EvalResult(
            String sampleId,
            RiskScore.RiskLevel overallRisk,
            List<RiskScore> riskScores,
            ReviewResult reviewResult,
            GroundingReport groundingReport,
            long latencyMs
    ) {}

    // ── test ─────────────────────────────────────────────────────────────────

    @Test
    void runEvalAndWriteReport() throws Exception {

        List<EvalResult> results = new ArrayList<>();

        for (Object[] row : SAMPLES) {
            String sampleId = (String) row[0];

            SamplePrLoader.SamplePr sample = loader.load(sampleId);
            List<RiskScore> scores = riskScorer.score(sample.files());
            RiskScore.RiskLevel overallRisk = riskScorer.overallRisk(scores);

            long start = System.currentTimeMillis();
            OpenAIService.GenerateReviewResult generated = openAI.generateReview(
                    sample.metadata(), sample.files(), scores, List.of());
            long latencyMs = System.currentTimeMillis() - start;

            GroundingReport grounding = grounder.validate(generated.reviewResult(), sample.files());

            results.add(new EvalResult(sampleId, overallRisk, scores,
                    generated.reviewResult(), grounding, latencyMs));
        }

        writeMarkdownReport(results);
        assertCorrectness(results);
    }

    // ── assertions ───────────────────────────────────────────────────────────

    private void assertCorrectness(List<EvalResult> results) {
        for (int i = 0; i < results.size(); i++) {
            EvalResult r = results.get(i);
            RiskScore.RiskLevel expectedMinRisk = (RiskScore.RiskLevel) SAMPLES[i][1];

            // Structural validation: all required fields present
            assertThat(r.reviewResult().summary())
                    .as("%s: summary must not be null", r.sampleId()).isNotNull();
            assertThat(r.reviewResult().summary().overview())
                    .as("%s: overview must not be blank", r.sampleId()).isNotBlank();
            assertThat(r.reviewResult().riskAssessment())
                    .as("%s: riskAssessment must not be null", r.sampleId()).isNotNull();
            assertThat(r.reviewResult().suggestedTests())
                    .as("%s: suggestedTests must not be empty", r.sampleId()).isNotEmpty();
            assertThat(r.reviewResult().reviewChecklist())
                    .as("%s: reviewChecklist must not be empty", r.sampleId()).isNotEmpty();

            // Risk scoring: deterministic rules must produce at least the expected minimum risk
            assertThat(r.overallRisk().ordinal())
                    .as("%s: overall risk should be >= %s", r.sampleId(), expectedMinRisk)
                    .isGreaterThanOrEqualTo(expectedMinRisk.ordinal());
        }
    }

    // ── report writer ────────────────────────────────────────────────────────

    private void writeMarkdownReport(List<EvalResult> results) throws Exception {
        StringBuilder md = new StringBuilder();

        md.append("# PatchLens Eval Report\n\n");
        md.append("Generated: ").append(LocalDate.now()).append("  \n");
        md.append("AI mode: **mock** (deterministic fixture — grounding rate reflects mock output, not real AI)\n\n");
        md.append("---\n\n");

        // Summary table
        md.append("## Summary\n\n");
        md.append("| Sample | Overall Risk | Risky Files (AI) | Grounding Rate | Suggested Tests | Checklist Items |\n");
        md.append("|--------|:---:|:---:|:---:|:---:|:---:|\n");
        for (EvalResult r : results) {
            md.append("| ").append(r.sampleId())
              .append(" | ").append(r.overallRisk())
              .append(" | ").append(r.reviewResult().riskAssessment().riskyFiles().size())
              .append(" | ").append(String.format("%.0f%%", r.groundingReport().groundingRate() * 100))
              .append(" | ").append(r.reviewResult().suggestedTests().size())
              .append(" | ").append(r.reviewResult().reviewChecklist().size())
              .append(" |\n");
        }

        // Per-sample detail
        for (EvalResult r : results) {
            md.append("\n---\n\n");
            md.append("## ").append(r.sampleId()).append("\n\n");

            md.append("**Overall risk (rule-based):** ").append(r.overallRisk()).append("  \n");
            md.append("**AI summary:** ").append(r.reviewResult().summary().overview()).append("\n\n");

            md.append("### Rule-Based Risk Scores (top 5)\n\n");
            md.append("| File | Risk | Score | Reasons |\n");
            md.append("|------|:----:|:-----:|---------|\n");
            r.riskScores().stream().limit(5).forEach(rs ->
                md.append("| ").append(rs.filename())
                  .append(" | ").append(rs.riskLevel())
                  .append(" | ").append(rs.score())
                  .append(" | ").append(String.join("; ", rs.reasons()))
                  .append(" |\n")
            );

            md.append("\n### AI-Flagged Risky Files\n\n");
            List<ReviewResult.RiskyFile> riskyFiles = r.reviewResult().riskAssessment().riskyFiles();
            if (riskyFiles.isEmpty()) {
                md.append("_(none)_\n");
            } else {
                md.append("| Path | Risk | Reason |\n");
                md.append("|------|:----:|--------|\n");
                riskyFiles.forEach(rf ->
                    md.append("| ").append(rf.path())
                      .append(" | ").append(rf.riskLevel())
                      .append(" | ").append(rf.reason())
                      .append(" |\n")
                );
            }

            md.append("\n### Grounding Report\n\n");
            GroundingReport g = r.groundingReport();
            md.append("- Total risky-file refs: ").append(g.totalRiskyFiles()).append("  \n");
            md.append("- Grounded: ").append(g.groundedCount()).append("  \n");
            md.append("- Hallucinated: ").append(g.hallucinatedCount()).append("  \n");
            if (!g.hallucinatedPaths().isEmpty()) {
                md.append("- Hallucinated paths: `").append(String.join("`, `", g.hallucinatedPaths())).append("`  \n");
            }
            md.append("- Grounding rate: **").append(String.format("%.0f%%", g.groundingRate() * 100)).append("**\n");

            md.append("\n### Suggested Tests\n\n");
            r.reviewResult().suggestedTests().forEach(t -> md.append("- ").append(t).append("\n"));

            md.append("\n### Reviewer Checklist\n\n");
            r.reviewResult().reviewChecklist().forEach(c -> md.append("- ").append(c).append("\n"));
        }

        Path out = Path.of("target/eval-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println("[EvalRunner] Report written to " + out.toAbsolutePath());
    }
}
