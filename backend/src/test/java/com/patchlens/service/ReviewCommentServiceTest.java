package com.patchlens.service;

import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.FindingCategory;
import com.patchlens.model.FindingSeverity;
import com.patchlens.model.ReviewFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that posting a review comment is safe under redelivery / retry:
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

    @BeforeEach
    void setUp() {
        service = new ReviewCommentService(gitHubService, "test-token");
        findings = List.of(new ReviewFinding(
                JOB_ID, "src/Foo.java", 10, 12,
                FindingCategory.CORRECTNESS, FindingSeverity.HIGH,
                "Null dereference", "x may be null", 0.9, null));
    }

    @Test
    void firstAttempt_postsNewCommentWithJobMarker() {
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(SHA));
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM)).thenReturn(List.of(
                new GitHubService.IssueComment(1L, "LGTM from a human")));

        service.postReview(JOB_ID, SHA, OWNER, REPO, PR_NUM, findings, List.of());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gitHubService).postPrComment(eq(OWNER), eq(REPO), eq(PR_NUM), body.capture());
        assertThat(body.getValue()).startsWith(ReviewCommentService.markerFor(JOB_ID));
        verify(gitHubService, never()).updatePrComment(any(), any(), anyLong(), any());
    }

    @Test
    void retryAfterCommentAlreadyPosted_updatesInsteadOfDuplicating() {
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(SHA));
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM)).thenReturn(List.of(
                new GitHubService.IssueComment(1L, "LGTM from a human"),
                new GitHubService.IssueComment(99L, ReviewCommentService.markerFor(JOB_ID) + "\nold body")));

        service.postReview(JOB_ID, SHA, OWNER, REPO, PR_NUM, findings, List.of());

        verify(gitHubService).updatePrComment(eq(OWNER), eq(REPO), eq(99L), anyString());
        verify(gitHubService, never()).postPrComment(any(), any(), anyInt(), any());
    }

    @Test
    void otherJobsComment_isNotTouched() {
        UUID otherJob = UUID.fromString("99999999-2222-3333-4444-555555555555");
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(SHA));
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM)).thenReturn(List.of(
                new GitHubService.IssueComment(7L, ReviewCommentService.markerFor(otherJob) + "\nbody")));

        service.postReview(JOB_ID, SHA, OWNER, REPO, PR_NUM, findings, List.of());

        verify(gitHubService).postPrComment(eq(OWNER), eq(REPO), eq(PR_NUM), anyString());
        verify(gitHubService, never()).updatePrComment(any(), any(), anyLong(), any());
    }

    @Test
    void headMovedToNewerCommit_skipsStaleComment() {
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(NEW_SHA));

        service.postReview(JOB_ID, SHA, OWNER, REPO, PR_NUM, findings, List.of());

        verify(gitHubService, never()).listPrComments(any(), any(), anyInt());
        verify(gitHubService, never()).postPrComment(any(), any(), anyInt(), any());
        verify(gitHubService, never()).updatePrComment(any(), any(), anyLong(), any());
    }

    @Test
    void unknownHeadSha_skipsStalenessCheck() {
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM)).thenReturn(List.of());

        service.postReview(JOB_ID, "unknown", OWNER, REPO, PR_NUM, findings, List.of());

        verify(gitHubService, never()).fetchHeadSha(any(), any(), anyInt());
        verify(gitHubService).postPrComment(eq(OWNER), eq(REPO), eq(PR_NUM), anyString());
    }

    @Test
    void cannotListComments_skipsPostingAndDoesNotThrow() {
        when(gitHubService.fetchHeadSha(OWNER, REPO, PR_NUM)).thenReturn(Optional.of(SHA));
        when(gitHubService.listPrComments(OWNER, REPO, PR_NUM))
                .thenThrow(new GitHubApiException("GITHUB_API_ERROR", "boom"));

        assertThatCode(() -> service.postReview(JOB_ID, SHA, OWNER, REPO, PR_NUM, findings, List.of()))
                .doesNotThrowAnyException();
        verify(gitHubService, never()).postPrComment(any(), any(), anyInt(), any());
    }
}
