package com.patchlens.service;

import com.patchlens.model.JobStatus;
import com.patchlens.model.ReviewJob;
import com.patchlens.repository.ReviewJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the worker's claim step: redelivered messages for finished jobs are no-ops,
 * while retries and crash redeliveries of unfinished jobs are allowed to proceed.
 */
@ExtendWith(MockitoExtension.class)
class ReviewJobStartProcessingTest {

    private static final UUID JOB_ID = UUID.randomUUID();

    @Mock private ReviewJobRepository repository;
    @Mock private JobStatusEmitter emitter;

    private ReviewJobService service;

    @BeforeEach
    void setUp() {
        service = new ReviewJobService(repository, emitter);
    }

    private ReviewJob jobIn(JobStatus status) {
        ReviewJob job = new ReviewJob("acme", "backend", 42, "https://github.com/acme/backend/pull/42", "sha");
        job.setStatus(status);
        return job;
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"COMPLETED", "DEAD_LETTER", "SUPERSEDED"})
    void terminalJob_isNotClaimed(JobStatus status) {
        ReviewJob job = jobIn(status);
        when(repository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThat(service.startProcessing(JOB_ID)).isEmpty();

        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getAttemptCount()).isZero();
        verify(repository, never()).save(any());
        verifyNoInteractions(emitter);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"COMPLETED", "DEAD_LETTER", "SUPERSEDED"}, mode = EnumSource.Mode.EXCLUDE)
    void nonTerminalJob_isClaimedAndAttemptCounted(JobStatus status) {
        ReviewJob job = jobIn(status);
        when(repository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(repository.save(job)).thenReturn(job);

        Optional<ReviewJob> claimed = service.startProcessing(JOB_ID);

        assertThat(claimed).contains(job);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        verify(emitter).emit(job);
    }
}
