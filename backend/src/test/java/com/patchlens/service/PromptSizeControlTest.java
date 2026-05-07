package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.PullRequestMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eval tests: verifies that oversized PR inputs are capped and truncated
 * before reaching the model, preventing runaway token usage.
 */
@ExtendWith(MockitoExtension.class)
class PromptSizeControlTest {

    @Mock
    private RestClient restClient;

    private OpenAIService buildService(int maxFiles, int maxPatchChars) {
        return new OpenAIService(restClient, new ObjectMapper(), "gpt-4o-mini", "live", maxFiles, maxPatchChars);
    }

    private PullRequestMetadata metadata() {
        return new PullRequestMetadata(
                "owner", "repo", 1, "Large PR", "body",
                "https://github.com/owner/repo/pull/1");
    }

    // --- file cap ---

    @Test
    void promptShouldIncludeOnlyFirstNFiles() {
        var service = buildService(3, 4000);
        var files = IntStream.range(0, 10)
                .mapToObj(i -> new ChangedFile("File" + i + ".java", "modified", 5, 3, 8, null))
                .toList();

        String prompt = service.buildPrompt(metadata(), files, List.of(), List.of());

        assertThat(prompt).contains("File0.java", "File1.java", "File2.java");
        assertThat(prompt).doesNotContain("File3.java");
    }

    @Test
    void promptWithFewerFilesThanCapShouldIncludeAllFiles() {
        var service = buildService(10, 4000);
        var files = List.of(
                new ChangedFile("A.java", "modified", 5, 3, 8, null),
                new ChangedFile("B.java", "modified", 5, 3, 8, null)
        );

        String prompt = service.buildPrompt(metadata(), files, List.of(), List.of());

        assertThat(prompt).contains("A.java", "B.java");
    }

    // --- patch truncation ---

    @Test
    void longPatchShouldBeTruncatedWithMarker() {
        var service = buildService(20, 100);
        String longPatch = "x".repeat(200);
        var files = List.of(new ChangedFile("Big.java", "modified", 100, 50, 150, longPatch));

        String prompt = service.buildPrompt(metadata(), files, List.of(), List.of());

        assertThat(prompt).contains("... (truncated)");
        assertThat(prompt).doesNotContain(longPatch);
    }

    @Test
    void shortPatchShouldNotBeTruncated() {
        var service = buildService(20, 100);
        String shortPatch = "short patch content";
        var files = List.of(new ChangedFile("Small.java", "modified", 5, 2, 7, shortPatch));

        String prompt = service.buildPrompt(metadata(), files, List.of(), List.of());

        assertThat(prompt).contains(shortPatch);
        assertThat(prompt).doesNotContain("... (truncated)");
    }

    @Test
    void patchExactlyAtLimitShouldNotBeTruncated() {
        var service = buildService(20, 100);
        String exactPatch = "x".repeat(100);
        var files = List.of(new ChangedFile("Exact.java", "modified", 10, 5, 15, exactPatch));

        String prompt = service.buildPrompt(metadata(), files, List.of(), List.of());

        assertThat(prompt).contains(exactPatch);
        assertThat(prompt).doesNotContain("... (truncated)");
    }
}
