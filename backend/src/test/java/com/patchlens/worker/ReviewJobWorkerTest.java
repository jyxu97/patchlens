package com.patchlens.worker;

import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.service.PatchGenerationService;
import com.patchlens.service.ReviewCommentService;
import com.patchlens.service.ReviewJobService;
import com.patchlens.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * A redelivered message for a job that already reached a terminal state must be
 * acked (listener returns normally) without re-running analysis or posting a comment.
 */
@ExtendWith(MockitoExtension.class)
class ReviewJobWorkerTest {

    @Mock private ReviewJobService reviewJobService;
    @Mock private ReviewService reviewService;
    @Mock private PatchGenerationService patchGenerationService;
    @Mock private ReviewCommentService reviewCommentService;

    @InjectMocks private ReviewJobWorker worker;

    @Test
    void redeliveryOfFinishedJob_isNoOp() {
        UUID jobId = UUID.randomUUID();
        when(reviewJobService.startProcessing(jobId)).thenReturn(Optional.empty());

        assertThatCode(() -> worker.process(
                new ReviewJobMessage(jobId, "acme", "backend", 42, "https://github.com/acme/backend/pull/42")))
                .doesNotThrowAnyException();

        verifyNoInteractions(reviewService, patchGenerationService, reviewCommentService);
        verify(reviewJobService, never()).markCompleted(any(), any(), any(), anyInt());
        verify(reviewJobService, never()).markFailed(any(), any());
    }
}
