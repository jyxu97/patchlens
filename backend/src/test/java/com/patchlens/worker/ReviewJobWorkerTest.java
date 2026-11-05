package com.patchlens.worker;

import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.model.*;
import com.patchlens.sandbox.GitHubSnapshotService;
import com.patchlens.sandbox.RepositorySnapshot;
import com.patchlens.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewJobWorkerTest {

    private static final String SHA = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    @Mock private ReviewJobService reviewJobService;
    @Mock private ReviewService reviewService;
    @Mock private PatchGenerationService patchGenerationService;
    @Mock private PatchPipelineService patchPipelineService;
    @Mock private GitHubService gitHubService;
    @Mock private GitHubSnapshotService snapshotService;
    @Mock private ReviewCommentService reviewCommentService;
    @Mock private CommentDeliveryService commentDeliveryService;

    @InjectMocks private ReviewJobWorker worker;

    private final UUID jobId = UUID.randomUUID();
    private final ReviewJobMessage message =
            new ReviewJobMessage(jobId, "acme", "backend", 42, "https://github.com/acme/backend/pull/42");
    private JobLease lease;
    private ReviewFinding finding;
    private PatchSuggestion patch;
    private RepositorySnapshot snapshot;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<JobLease> c = JobLease.class.getDeclaredConstructor(UUID.class, String.class);
        c.setAccessible(true);
        lease = c.newInstance(jobId, "token");

        ReviewJob job = new ReviewJob("acme", "backend", 42, message.pullRequestUrl(), SHA);
        when(reviewJobService.findById(jobId)).thenReturn(Optional.of(job));
        finding = new ReviewFinding(jobId, "src/A.java", 1, 2, FindingCategory.CORRECTNESS,
                FindingSeverity.HIGH, "t", "e", 0.9, null);
        ChangedFile file = new ChangedFile("src/A.java", "modified", 1, 1, 2, "@@ -1 +1 @@\n-a\n+b");
        when(reviewService.runAnalysisV2(eq(jobId), any(), any(), anyInt(), any()))
                .thenReturn(new ReviewService.AnalysisOutcomeV2(mock(ReviewService.AnalysisOutcome.class),
                        List.of(finding), List.of(file), List.of()));
        when(gitHubService.fetchHeadSha("acme", "backend", 42)).thenReturn(Optional.of(SHA));
        patch = new PatchSuggestion(finding.getId(), "--- a/src/A.java", "r");
        when(patchGenerationService.generateCandidates(any(), any(), any()))
                .thenReturn(List.of(new PatchGenerationService.Candidate(finding, patch)));
        snapshot = mock(RepositorySnapshot.class);
        when(snapshotService.snapshot("acme", "backend", SHA)).thenReturn(snapshot);
        when(patchPipelineService.validateAndRepair(finding, patch, snapshot))
                .thenReturn(new PatchPipelineService.PatchResult(finding, patch, List.of()));
        when(reviewCommentService.canPost()).thenReturn(true);
        when(reviewCommentService.render(eq(jobId), any(), any())).thenReturn(Optional.of("<!-- marker --> body"));
    }

    @Test
    void unclaimableMessage_isAckedWithoutDoingAnyWork() {
        when(reviewJobService.claim(jobId)).thenReturn(Optional.empty());

        assertThatCode(() -> worker.process(message)).doesNotThrowAnyException();

        verifyNoInteractions(reviewService, patchGenerationService, patchPipelineService, commentDeliveryService);
        verify(reviewJobService, never()).complete(any(), any(), any(), anyInt(), any());
    }

    @Test
    void normalWorkflow_validatesInSandbox_storesCommentDurably_thenDelivers() {
        when(reviewJobService.claim(jobId)).thenReturn(Optional.of(lease));

        worker.process(message);

        InOrder order = inOrder(reviewJobService, patchPipelineService, commentDeliveryService);
        order.verify(reviewJobService).advance(lease, JobStatus.GENERATING_FINDINGS);
        order.verify(reviewJobService).advance(lease, JobStatus.GENERATING_PATCHES);
        order.verify(reviewJobService).advance(lease, JobStatus.VALIDATING_PATCHES);
        order.verify(patchPipelineService).validateAndRepair(finding, patch, snapshot);
        order.verify(reviewJobService).complete(eq(lease), any(), any(), eq(1),
                eq(ReviewJobService.CommentPlan.deliver("<!-- marker --> body")));
        order.verify(commentDeliveryService).deliver(jobId);
    }

    @Test
    void headMovedDuringAnalysis_jobIsSupersededAndNothingIsValidatedOrPosted() {
        when(reviewJobService.claim(jobId)).thenReturn(Optional.of(lease));
        when(gitHubService.fetchHeadSha("acme", "backend", 42))
                .thenReturn(Optional.of("ffffffffffffffffffffffffffffffffffffffff"));

        worker.process(message);

        verify(reviewJobService).supersede(eq(lease), contains(SHA));
        verifyNoInteractions(patchGenerationService, patchPipelineService, commentDeliveryService);
        verify(reviewJobService, never()).complete(any(), any(), any(), anyInt(), any());
    }

    @Test
    void lostLease_abandonsWithoutFailingOrCompletingTheJob() {
        when(reviewJobService.claim(jobId)).thenReturn(Optional.of(lease));
        when(reviewJobService.advance(lease, JobStatus.GENERATING_PATCHES))
                .thenThrow(new JobLease.LeaseLostException(jobId));

        assertThatCode(() -> worker.process(message)).doesNotThrowAnyException();

        verify(reviewJobService, never()).fail(any(), any());
        verify(reviewJobService, never()).complete(any(), any(), any(), anyInt(), any());
        verifyNoInteractions(commentDeliveryService);
    }

    @Test
    void noGitHubToken_completesWithoutACommentToDeliver() {
        when(reviewJobService.claim(jobId)).thenReturn(Optional.of(lease));
        when(reviewCommentService.canPost()).thenReturn(false);

        worker.process(message);

        verify(reviewJobService).complete(eq(lease), any(), any(), eq(1), eq(ReviewJobService.CommentPlan.none()));
    }

    @Test
    void transientFailure_releasesLeaseAndRethrowsForBrokerRetry() {
        when(reviewJobService.claim(jobId)).thenReturn(Optional.of(lease));
        when(patchGenerationService.generateCandidates(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.process(message))
                .isInstanceOf(IllegalStateException.class);
        verify(reviewJobService).fail(lease, "db down");
        verifyNoInteractions(commentDeliveryService);
    }
}
