package com.patchlens.service;

import com.patchlens.model.IndexFreshness;
import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.ReviewCoverage;
import com.patchlens.model.ReviewFinding;
import com.patchlens.model.ValidationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Formats PatchLens review findings and patches as a Markdown PR comment and delivers it via
 * the GitHub Issues API. Rendering and delivery are separate: the rendered body is stored on the
 * job so {@link CommentDeliveryService} can retry delivery without re-running analysis.
 *
 * The comment is intentionally advisory (no approve/reject):
 * the developer decides whether to accept, modify, or ignore each fix.
 */
@Service
public class ReviewCommentService {

    private static final Logger log = LoggerFactory.getLogger(ReviewCommentService.class);

    private static final int MAX_PATCH_PREVIEW_LINES = 40;

    private final GitHubService gitHubService;
    private final String githubToken;

    public ReviewCommentService(GitHubService gitHubService,
                                @Value("${github.token:}") String githubToken) {
        this.gitHubService = gitHubService;
        this.githubToken = githubToken;
    }

    /** True if comments can be written at all (an authenticated token is configured). */
    public boolean canPost() {
        return githubToken != null && !githubToken.isBlank();
    }

    /**
     * Renders the job's review comment, starting with the hidden job marker.
     * Empty when there is nothing to post (no findings).
     */
    public Optional<String> render(UUID jobId, List<ReviewFinding> findings, List<PatchSuggestion> patches) {
        return render(jobId, findings, patches, null);
    }

    /** As above, with a coverage footer (reviewed/skipped files, context freshness). */
    public Optional<String> render(UUID jobId, List<ReviewFinding> findings, List<PatchSuggestion> patches,
                                   ReviewCoverage coverage) {
        if (findings.isEmpty()) return Optional.empty();
        return Optional.of(markerFor(jobId) + "\n" + buildComment(findings, patches) + coverageFooter(coverage));
    }

    static String coverageFooter(ReviewCoverage c) {
        if (c == null) return "";
        StringBuilder sb = new StringBuilder("\n<sub>Reviewed ")
                .append(c.reviewedFiles().size()).append(" of ").append(c.totalFiles()).append(" changed file(s)");
        if (!c.skippedFiles().isEmpty()) {
            Map<ReviewCoverage.Reason, Long> byReason = c.skippedFiles().stream()
                    .collect(Collectors.groupingBy(ReviewCoverage.SkippedFile::reason, java.util.TreeMap::new, Collectors.counting()));
            sb.append(" — not reviewed: ");
            sb.append(byReason.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase().replace('_', ' '))
                    .collect(Collectors.joining(", ")));
        }
        if (!c.truncatedFiles().isEmpty()) {
            sb.append("; ").append(c.truncatedFiles().size()).append(" diff(s) truncated to ").append(c.maxPatchChars()).append(" chars");
        }
        IndexFreshness f = c.indexFreshness();
        sb.append(". Repository context: ").append(f == null ? "not checked" : f.status().name().toLowerCase().replace('_', ' '));
        if (f != null && f.indexedRevision() != null) {
            sb.append(" (index ").append(f.indexedRevision(), 0, Math.min(7, f.indexedRevision().length())).append(")");
        }
        return sb.append(".</sub>\n").toString();
    }

    /** Result of one delivery attempt. */
    public sealed interface Delivery permits Delivered, SkippedStale {}
    public record Delivered(long commentId, boolean updatedExisting) implements Delivery {}
    public record SkippedStale(String currentHead) implements Delivery {}

    /**
     * Delivers a rendered comment: updates the job's existing comment (found by its hidden marker)
     * or creates it. Idempotent across retries and ambiguous outcomes — if a previous POST reached
     * GitHub but the response was lost, the marker is found and the comment updated, not duplicated.
     *
     * <p>Stale-safe: if the PR head moved past {@code headSha}, nothing is posted.
     *
     * @throws com.patchlens.exception.GitHubApiException or another runtime exception on failure —
     *         the caller records the attempt and decides whether to retry
     */
    public Delivery deliver(UUID jobId, String headSha, String owner, String repo, int pullNumber, String body) {
        if (headSha != null && !headSha.isBlank() && !"unknown".equals(headSha)) {
            Optional<String> current = gitHubService.fetchHeadSha(owner, repo, pullNumber);
            if (current.isPresent() && !current.get().equals(headSha)) {
                log.info("PR {}/{} #{} head moved past {} — skipping stale review comment for job {}",
                        owner, repo, pullNumber, headSha, jobId);
                return new SkippedStale(current.get());
            }
        }

        String marker = markerFor(jobId);
        Optional<GitHubService.IssueComment> existing = gitHubService
                .listPrComments(owner, repo, pullNumber).stream()
                .filter(c -> c.body() != null && c.body().contains(marker))
                .findFirst();

        if (existing.isPresent()) {
            gitHubService.updatePrComment(owner, repo, existing.get().id(), body);
            return new Delivered(existing.get().id(), true);
        }
        return new Delivered(gitHubService.createPrComment(owner, repo, pullNumber, body), false);
    }

    /** Hidden HTML marker identifying the comment owned by a review job. */
    static String markerFor(UUID jobId) {
        return "<!-- patchlens:job=" + jobId + " -->";
    }

    // =====================================================================
    // Formatting
    // =====================================================================

    private String buildComment(List<ReviewFinding> findings, List<PatchSuggestion> patches) {
        Map<UUID, PatchSuggestion> patchByFinding = patches.stream()
                .collect(Collectors.toMap(PatchSuggestion::getFindingId, p -> p,
                        (a, b) -> a));  // keep first if duplicate (shouldn't happen)

        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("### 🔍 PatchLens Review — ")
          .append(findings.size()).append(" finding(s)\n\n");
        sb.append("> Generated by [PatchLens](https://patchlens-alpha.vercel.app). ")
          .append("Findings are advisory — accept, modify, or ignore at your discretion.\n\n");
        sb.append("---\n\n");

        for (ReviewFinding f : findings) {
            appendFinding(sb, f, patchByFinding.get(f.getId()));
        }

        sb.append("---\n");
        sb.append("*PatchLens — AI-assisted code review with sandboxed patch validation*\n");
        return sb.toString();
    }

    private void appendFinding(StringBuilder sb, ReviewFinding finding, PatchSuggestion patch) {
        String severityEmoji = switch (finding.getSeverity()) {
            case HIGH   -> "🔴";
            case MEDIUM -> "🟡";
            case LOW    -> "🟢";
        };

        sb.append("#### ")
          .append(severityEmoji).append(" **[").append(finding.getSeverity()).append("] ")
          .append(finding.getCategory()).append("** — `")
          .append(finding.getFilePath()).append(":")
          .append(finding.getLineStart()).append("–").append(finding.getLineEnd())
          .append("`\n\n");

        sb.append("**").append(finding.getTitle()).append("**\n\n");
        sb.append(finding.getExplanation()).append("\n\n");

        // Patch section (only HIGH/MEDIUM findings get one)
        if (patch != null) {
            String patchText = patch.getPatchText();
            if (patchText != null && !patchText.isBlank()) {
                // Only a sandbox-validated patch is presented as a suggested fix.
                String summary = patch.getStatus() == ValidationStatus.VALIDATED
                        ? "Suggested patch (validated in sandbox)"
                        : "Unverified patch — did not pass validation, do not apply as-is";
                sb.append("<details>\n<summary>").append(summary).append("</summary>\n\n");
                sb.append("```diff\n");
                sb.append(truncateLines(patchText, MAX_PATCH_PREVIEW_LINES));
                sb.append("\n```\n\n");

                sb.append("**Validation** — ");
                sb.append(validationLabel(patch.getStatus()));
                if (patch.getRepairAttempts() > 0) {
                    sb.append(" · ").append(patch.getRepairAttempts())
                      .append(" repair attempt(s)");
                }
                sb.append("\n\n");
                sb.append("</details>\n\n");
            }
        }

        sb.append("---\n\n");
    }

    static String validationLabel(ValidationStatus status) {
        return switch (status) {
            case VALIDATED               -> "✅ Applied, compiled and passed tests in sandbox";
            case REJECTED_PATCH_APPLY    -> "❌ Patch did not apply to the reviewed commit";
            case REJECTED_COMPILE        -> "❌ Compile failed";
            case REJECTED_STATIC_ANALYSIS -> "❌ Static analysis failed";
            case REJECTED_TEST           -> "❌ Tests failed";
            case REJECTED_POLICY         -> "❌ Rejected by patch policy (format/scope/size)";
            case NOT_VALIDATED           -> "⚪ Not validated — sandbox unavailable";
            case VALIDATION_ERROR        -> "⚠️ Validation could not run (infrastructure error)";
            case PENDING                 -> "⏳ Pending validation";
        };
    }

    private String truncateLines(String text, int maxLines) {
        String[] lines = text.split("\n");
        if (lines.length <= maxLines) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("... (").append(lines.length - maxLines).append(" more lines)");
        return sb.toString();
    }
}
