package com.patchlens.service;

import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Rendering and idempotent delivery of the job's review comment:
 * one comment per job (updated in place on repeat), and no comment for a stale commit.
 */
@ExtendWith(MockitoExtension.class)
class ReviewCommentServiceTest {

    private static final String OWNER   = "acme";
    private static final String REPO    = "backend";
    private static final int    PR_NUM  = 42;
    private static final String SHA     = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    private static final String NEW_SHA = "ffffffffffffffffffffffffffffffffffffffff";
    private static final UUID   JOB_ID  = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private GitHubService gitHubService;

    private ReviewCommentService service;
    private List<ReviewFinding> findings;
    private String body;

    @BeforeEach
    void setUp() {
        service = new ReviewCommentService(gitHubService, "test-token");
        findings = List.of(new ReviewFinding(
                JOB_ID, "src/Foo.java", 10, 12,
                FindingCategory.CORRECTNESS, FindingSeverity.HIGH,
                "Null dereference", "x may be null", 0.9, null));
        body = service.render(JOB_ID, findings, List.of()).orElseThrow();
    }

    @Test
    void renderStartsWithJobMarker_andIsEmptyWithoutFindings() {
        assertThat(body).startsWith(ReviewCommentService.markerFor(JOB_ID));
        assertThat(service.render(JOB_ID, List.of(), List.of())).isEmpty();
    }

    @Test
    void onlySandboxValidatedPatchesArePresentedAsFixes() {
        // findings and patches are unsaved here, so both ids are null and still join up
        PatchSuggestion failed = new PatchSuggestion(null, "--- a/src/Foo.java\n+++ b/src/Foo.java\n@@ -1 +1 @@\n-a\n+b", "r");
        failed.setStatus(ValidationStatus.REJECTED_TEST);
        PatchSuggestion unvalidated = new PatchSuggestion(null, failed.getPatchText(), "r");
        unvalidated.setStatus(ValidationStatus.NOT_VALIDATED);

        String failedBody = service.render(JOB_ID, findings, List.of(failed)).orElseThrow();
        assertThat(failedBody).contains("Unverified patch").contains("Tests failed").doesNotContain("Suggested patch (validated");

        String unvalidatedBody = service.render(JOB_ID, findings, List.of(unvalidated)).orElseThrow();
        assertThat(unvalidatedBody).contains("Unverified patch").contains("Not validated");
    }

    @Test
    void firstAttempt_createsCommentWithJobMarker() {
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(SHA));
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM)).thenReturn(List.of(
                new GitHubService.IssueComment(1L, "LGTM from a human")));
        when(gitHubService.createPrComment(OWNER, REPO, PR_NUM, body)).thenReturn(77L);

        var d = service.deliver(JOB_ID, SHA, OWNER, REPO, PR_NUM, body);

        assertThat(d).isEqualTo(new ReviewCommentService.Delivered(77L, false));
        verify(gitHubService, never()).updatePrComment(any(), any(), anyLong(), any());
    }

    @Test
    void retryAfterCommentAlreadyPosted_updatesInsteadOfDuplicating() {
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(SHA));
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM)).thenReturn(List.of(
                new GitHubService.IssueComment(1L, "LGTM from a human"),
                new GitHubService.IssueComment(99L, ReviewCommentService.markerFor(JOB_ID) + "\nold body")));

        var d = service.deliver(JOB_ID, SHA, OWNER, REPO, PR_NUM, body);

        assertThat(d).isEqualTo(new ReviewCommentService.Delivered(99L, true));
        verify(gitHubService).updatePrComment(OWNER, REPO, 99L, body);
        verify(gitHubService, never()).createPrComment(any(), any(), anyInt(), any());
    }

    @Test
    void otherJobsComment_isNotTouched() {
        UUID otherJob = UUID.fromString("99999999-2222-3333-4444-555555555555");
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(SHA));
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM)).thenReturn(List.of(
                new GitHubService.IssueComment(7L, ReviewCommentService.markerFor(otherJob) + "\nbody")));
        when(gitHubService.createPrComment(OWNER, REPO, PR_NUM, body)).thenReturn(8L);

        service.deliver(JOB_ID, SHA, OWNER, REPO, PR_NUM, body);

        verify(gitHubService, never()).updatePrComment(any(), any(), anyLong(), any());
    }

    @Test
    void headMovedToNewerCommit_skipsStaleComment() {
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(NEW_SHA));

        var d = service.deliver(JOB_ID, SHA, OWNER, REPO, PR_NUM, body);

        assertThat(d).isEqualTo(new ReviewCommentService.SkippedStale(NEW_SHA));
        verify(gitHubService, never()).listPrComments(any(), any(), anyInt());
        verify(gitHubService, never()).createPrComment(any(), any(), anyInt(), any());
    }

    @Test
    void unknownHeadSha_skipsStalenessCheck() {
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM)).thenReturn(List.of());
        when(gitHubService.createPrComment(OWNER, REPO, PR_NUM, body)).thenReturn(5L);

        service.deliver(JOB_ID, "unknown", OWNER, REPO, PR_NUM, body);

        verify(gitHubService, never()).fetchHeadSha(any(), any(), anyInt());
    }

    @Test
    void cannotListComments_throwsSoTheDurableRetryHandlesIt_andNothingIsPosted() {
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(SHA));
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM))
                .thenThrow(new GitHubApiException("GITHUB_API_ERROR", "boom", 502));

        assertThatThrownBy(() -> service.deliver(JOB_ID, SHA, OWNER, REPO, PR_NUM, body))
                .isInstanceOf(GitHubApiException.class);
        verify(gitHubService, never()).createPrComment(any(), any(), anyInt(), any());
    }
}
