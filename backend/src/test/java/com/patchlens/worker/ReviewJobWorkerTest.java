package com.patchlens.worker;

import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.model.*;
import com.patchlens.sandbox.GitHubSnapshotService;
import com.patchlens.sandbox.RepositorySnapshot;
import com.patchlens.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewJobWorkerTest {

    private static final String SHA = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    @Mock private ReviewJobService reviewJobService;
    @Mock private ReviewService reviewService;
    @Mock private PatchGenerationService patchGenerationService;
    @Mock private PatchPipelineService patchPipelineService;
    @Mock private GitHubService gitHubService;
    @Mock private GitHubSnapshotService snapshotService;
    @Mock private ReviewCommentService reviewCommentService;

    @InjectMocks private ReviewJobWorker worker;

    private final UUID jobId = UUID.randomUUID();
    private final ReviewJobMessage message =
            new ReviewJobMessage(jobId, "acme", "backend", 42, "https://github.com/acme/backend/pull/42");

    /**
     * A redelivered message for a job that already reached a terminal state must be
     * acked (listener returns normally) without re-running analysis or posting a comment.
     */
    @Test
    void redeliveryOfFinishedJob_isNoOp() {
        when(reviewJobService.startProcessing(jobId)).thenReturn(Optional.empty());

        assertThatCode(() -> worker.process(message)).doesNotThrowAnyException();

        verifyNoInteractions(reviewService, patchGenerationService, patchPipelineService, reviewCommentService);
        verify(reviewJobService, never()).markCompleted(any(), any(), any(), anyInt());
        verify(reviewJobService, never()).markFailed(any(), any());
    }

    @Test
    void normalWorkflow_validatesEveryCandidateInSandboxAgainstPinnedSnapshot() {
        ReviewJob job = new ReviewJob("acme", "backend", 42, message.pullRequestUrl(), SHA);
        when(reviewJobService.startProcessing(jobId)).thenReturn(Optional.of(job));

        ReviewFinding finding = new ReviewFinding(jobId, "src/A.java", 1, 2, FindingCategory.CORRECTNESS,
                FindingSeverity.HIGH, "t", "e", 0.9, null);
        ChangedFile file = new ChangedFile("src/A.java", "modified", 1, 1, 2, "@@ -1 +1 @@\n-a\n+b");
        ReviewService.AnalysisOutcome base = mock(ReviewService.AnalysisOutcome.class);
        when(reviewService.runAnalysisV2(eq(jobId), any(), any(), anyInt(), any()))
                .thenReturn(new ReviewService.AnalysisOutcomeV2(base, List.of(finding), List.of(file), List.of()));
        when(gitHubService.fetchHeadSha("acme", "backend", 42)).thenReturn(Optional.of(SHA));

        PatchSuggestion patch = new PatchSuggestion(finding.getId(), "--- a/src/A.java", "r");
        when(patchGenerationService.generateCandidates(any(), any(), any()))
                .thenReturn(List.of(new PatchGenerationService.Candidate(finding, patch)));
        RepositorySnapshot snapshot = mock(RepositorySnapshot.class);
        when(snapshotService.snapshot("acme", "backend", SHA)).thenReturn(snapshot);
        when(patchPipelineService.validateAndRepair(finding, patch, snapshot))
                .thenReturn(new PatchPipelineService.PatchResult(finding, patch, List.of()));

        worker.process(message);

        InOrder order = inOrder(reviewJobService, patchPipelineService, reviewCommentService);
        order.verify(reviewJobService).markGeneratingPatches(jobId);
        order.verify(reviewJobService).markValidatingPatches(jobId);
        order.verify(patchPipelineService).validateAndRepair(finding, patch, snapshot);
        order.verify(reviewCommentService).postReview(eq(jobId), eq(SHA), any(), any(), anyInt(), any(), eq(List.of(patch)));
        order.verify(reviewJobService).markCompleted(eq(jobId), any(), any(), eq(1));
    }

    @Test
    void headMovedDuringAnalysis_jobIsSupersededAndNothingIsValidatedOrPosted() {
        ReviewJob job = new ReviewJob("acme", "backend", 42, message.pullRequestUrl(), SHA);
        when(reviewJobService.startProcessing(jobId)).thenReturn(Optional.of(job));
        when(reviewService.runAnalysisV2(eq(jobId), any(), any(), anyInt(), any()))
                .thenReturn(new ReviewService.AnalysisOutcomeV2(mock(ReviewService.AnalysisOutcome.class),
                        List.of(), List.of(), List.of()));
        when(gitHubService.fetchHeadSha("acme", "backend", 42))
                .thenReturn(Optional.of("ffffffffffffffffffffffffffffffffffffffff"));

        worker.process(message);

        verify(reviewJobService).markSuperseded(eq(jobId), contains(SHA));
        verifyNoInteractions(patchGenerationService, patchPipelineService, reviewCommentService);
        verify(reviewJobService, never()).markCompleted(any(), any(), any(), anyInt());
    }
}
