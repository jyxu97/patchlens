package com.patchlens.service;

import com.patchlens.model.PullRequestMetadata;
import com.patchlens.model.ReviewResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OpenAIServiceTest {

    @Mock
    private RestClient restClient;

    private OpenAIService buildService(String aiMode) {
        return new OpenAIService(restClient, new ObjectMapper(), "gpt-4o-mini", aiMode, 20, 4000);
    }

    private PullRequestMetadata sampleMetadata() {
        return new PullRequestMetadata(
                "owner", "repo", 1, "My Test PR", "body",
                "https://github.com/owner/repo/pull/1");
    }

    // --- mock mode ---

    @Test
    void mockModeShouldNotCallOpenAI() {
        var service = buildService("mock");

        service.generateReview(sampleMetadata(), List.of(), List.of(), List.of());

        verifyNoInteractions(restClient);
    }

    @Test
    void mockResultTitleShouldMatchInputPrTitle() {
        var service = buildService("mock");
        var metadata = sampleMetadata();

        ReviewResult result = service.generateReview(metadata, List.of(), List.of(), List.of());

        assertThat(result.summary().title()).isEqualTo(metadata.title());
    }

    @Test
    void mockResultShouldHaveAllRequiredFields() {
        var service = buildService("mock");

        ReviewResult result = service.generateReview(sampleMetadata(), List.of(), List.of(), List.of());

        assertThat(result.summary()).isNotNull();
        assertThat(result.riskAssessment()).isNotNull();
        assertThat(result.suggestedTests()).isNotNull().isNotEmpty();
        assertThat(result.reviewChecklist()).isNotNull().isNotEmpty();
    }

    @Test
    void mockModeShouldHandleNullContextChunksWithoutThrowing() {
        var service = buildService("mock");

        ReviewResult result = service.generateReview(sampleMetadata(), List.of(), List.of(), null);

        assertThat(result).isNotNull();
    }

    @Test
    void mockModeShouldBeCaseInsensitive() {
        var service = buildService("MOCK");

        ReviewResult result = service.generateReview(sampleMetadata(), List.of(), List.of(), List.of());

        assertThat(result).isNotNull();
        verifyNoInteractions(restClient);
    }
}
