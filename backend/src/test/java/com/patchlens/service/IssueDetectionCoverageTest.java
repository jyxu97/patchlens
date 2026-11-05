package com.patchlens.service;

import com.patchlens.model.*;
import com.patchlens.repository.ReviewFindingRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Large-PR detection: bounded batches, recorded coverage, no findings on unreviewed files. */
class IssueDetectionCoverageTest {

    @Test
    void largePrIsReviewedInBoundedBatchesAndCoverageIsRecorded() {
        OpenAIService openAI = mock(OpenAIService.class);
        when(openAI.analyzeDiff(any(), any())).thenReturn(new OpenAIService.DiffUnderstanding(List.of(), List.of(), List.of()));
        List<Integer> batchSizes = new ArrayList<>();
        when(openAI.detectFindings(any(), anyList(), any(), any())).thenAnswer(inv -> {
            List<ChangedFile> batch = inv.getArgument(1);
            batchSizes.add(batch.size());
            ChangedFile first = batch.get(0);
            return List.of(
                    new OpenAIService.DetectedFinding(first.filename(), 1, 1, FindingCategory.CORRECTNESS, FindingSeverity.HIGH,
                            "real", "e", List.of(), 0.9),
                    // the model "sees" only its batch; claims about a file outside every batch are ungrounded
                    new OpenAIService.DetectedFinding("src/F29.java", 1, 1, FindingCategory.SECURITY, FindingSeverity.HIGH,
                            "about an unreviewed file", "e", List.of(), 0.9));
        });
        ContextRetrievalService retrieval = mock(ContextRetrievalService.class);
        when(retrieval.retrieve(any(), any(), any(), any())).thenReturn(List.of());
        ReviewFindingRepository repo = mock(ReviewFindingRepository.class);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        IssueDetectionService service = new IssueDetectionService(openAI, Optional.empty(), retrieval,
                new FindingFilterService(), repo, new ObjectMapper());
        service.configureLimits(20, 4_000, 5_000, 3);

        List<ChangedFile> files = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            files.add(new ChangedFile(String.format("src/F%02d.java", i), "modified", 1, 0, 1,
                    "@@ -1,1 +1,2 @@\n a\n+" + "b".repeat(1_500)));
        }
        IssueDetectionService.Detection d = service.detectWithCoverage(UUID.randomUUID(),
                new PullRequestMetadata("o", "r", 1, "t", "", "u"), files, List.of());

        ReviewCoverage c = d.coverage();
        assertThat(c.reviewedFiles()).hasSize(9);   // 3 batches × 3 files of ~1.5k chars within 5k
        assertThat(c.skippedFiles()).hasSize(21);
        // the batch budget (3 × 5k chars) is reached before the 20-file limit
        assertThat(c.skippedFiles()).extracting(ReviewCoverage.SkippedFile::reason)
                .containsOnly(ReviewCoverage.Reason.BATCH_LIMIT);
        assertThat(batchSizes).hasSize(3).allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(3));
        assertThat(d.findings()).extracting(ReviewFinding::getTitle).containsOnly("real");
        assertThat(d.findings()).allSatisfy(f -> assertThat(c.reviewedFiles()).contains(f.getFilePath()));
    }
}
