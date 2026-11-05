package com.patchlens.service;

import com.patchlens.model.PatchSuggestion;
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
 * Formats PatchLens review findings and patches as a Markdown PR comment
 * and posts it via the GitHub Issues API.
 *
 * Posting is skipped when no GitHub token is configured — unauthenticated
 * requests cannot write comments.
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

    /**
     * Formats findings and patches as Markdown and posts (or updates) the job's review comment.
     *
     * <p>Idempotent per job: the comment starts with a hidden marker containing the job ID.
     * If a comment with that marker already exists on the PR — e.g. a previous attempt posted
     * it and then crashed before the job was marked COMPLETED — it is updated in place instead
     * of posting a duplicate. The marker lives on GitHub, so this holds even when the crash
     * happens between the POST and any local bookkeeping.
     *
     * <p>Stale-safe: if the PR head has moved past {@code headSha}, the review describes an
     * outdated commit and is not posted; the newer commit's own job will post its review.
     *
     * <p>Never throws — comment failures must not fail an otherwise successful review job.
     *
     * @param jobId       owning review job (used as the idempotency marker)
     * @param headSha     commit SHA this job was created for (may be null / "unknown")
     * @param owner       GitHub repository owner
     * @param repo        GitHub repository name
     * @param pullNumber  PR number
     * @param findings    detected findings (may be empty — no comment posted if so)
     * @param patches     generated patches; may be fewer than findings (only HIGH/MEDIUM)
     */
    public void postReview(UUID jobId, String headSha,
                           String owner, String repo, int pullNumber,
                           List<ReviewFinding> findings,
                           List<PatchSuggestion> patches) {
        if (findings.isEmpty()) {
            log.info("No findings for {}/{} #{} — skipping review comment", owner, repo, pullNumber);
            return;
        }
        if (githubToken == null || githubToken.isBlank()) {
            log.info("No GitHub token configured — skipping review comment for {}/{} #{}", owner, repo, pullNumber);
            return;
        }

        try {
            if (isStale(headSha, owner, repo, pullNumber)) {
                log.info("PR {}/{} #{} head moved past {} — skipping stale review comment for job {}",
                        owner, repo, pullNumber, headSha, jobId);
                return;
            }

            String marker = markerFor(jobId);
            String body = marker + "\n" + buildComment(findings, patches);

            Optional<GitHubService.IssueComment> existing = gitHubService
                    .listPrComments(owner, repo, pullNumber).stream()
                    .filter(c -> c.body() != null && c.body().contains(marker))
                    .findFirst();

            if (existing.isPresent()) {
                gitHubService.updatePrComment(owner, repo, existing.get().id(), body);
            } else {
                gitHubService.postPrComment(owner, repo, pullNumber, body);
            }
        } catch (Exception e) {
            // If we can't verify whether a comment already exists, skip rather than risk a duplicate.
            log.warn("Could not post review comment for job {} on {}/{} #{}: {}",
                    jobId, owner, repo, pullNumber, e.getMessage());
        }
    }

    /** Hidden HTML marker identifying the comment owned by a review job. */
    static String markerFor(UUID jobId) {
        return "<!-- patchlens:job=" + jobId + " -->";
    }

    /**
     * True if the PR's current head differs from the commit this job reviewed.
     * Unknown SHAs (manual triggers, malformed payloads) can't be compared, so they are
     * treated as not stale.
     */
    private boolean isStale(String headSha, String owner, String repo, int pullNumber) {
        if (headSha == null || headSha.isBlank() || "unknown".equals(headSha)) {
            return false;
        }
        return gitHubService.fetchHeadSha(owner, repo, pullNumber)
                .map(current -> !current.equals(headSha))
                .orElse(false);
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
