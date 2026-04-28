package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.PullRequestMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffParserServiceTest {

    private DiffParserService service;

    private static final PullRequestMetadata METADATA = new PullRequestMetadata(
            "owner", "repo", 1, "Add feature", "PR body", "https://github.com/owner/repo/pull/1"
    );

    @BeforeEach
    void setUp() {
        service = new DiffParserService();
    }

    @Test
    void normalizedStringShouldContainPrTitleAndBody() {
        String result = service.normalize(METADATA, List.of());

        assertThat(result).contains("TITLE: Add feature");
        assertThat(result).contains("BODY: PR body");
    }

    @Test
    void normalizedStringShouldContainFileInfo() {
        var file = new ChangedFile("src/Foo.java", "modified", 10, 5, 15, "@@ -1 +1 @@");

        String result = service.normalize(METADATA, List.of(file));

        assertThat(result).contains("FILE: src/Foo.java");
        assertThat(result).contains("STATUS: modified");
        assertThat(result).contains("ADDITIONS: 10");
        assertThat(result).contains("DELETIONS: 5");
        assertThat(result).contains("PATCH:");
    }

    @Test
    void normalizedStringShouldBeSortedByFilename() {
        var fileB = new ChangedFile("src/B.java", "added", 1, 0, 1, null);
        var fileA = new ChangedFile("src/A.java", "added", 1, 0, 1, null);

        // Pass files in reverse order (B before A)
        String result = service.normalize(METADATA, List.of(fileB, fileA));

        // A should appear before B in the output regardless of input order
        assertThat(result.indexOf("FILE: src/A.java"))
                .isLessThan(result.indexOf("FILE: src/B.java"));
    }

    @Test
    void hashShouldBeDeterministic() {
        String diff = service.normalize(METADATA, List.of());

        assertThat(service.hash(diff)).isEqualTo(service.hash(diff));
    }

    @Test
    void hashShouldChangeWhenContentChanges() {
        var fileA = new ChangedFile("src/A.java", "modified", 1, 0, 1, null);
        var fileB = new ChangedFile("src/B.java", "modified", 1, 0, 1, null);

        String hashA = service.hash(service.normalize(METADATA, List.of(fileA)));
        String hashB = service.hash(service.normalize(METADATA, List.of(fileB)));

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void hashShouldBe64HexCharacters() {
        String hash = service.hash(service.normalize(METADATA, List.of()));

        // SHA-256 produces 32 bytes = 64 hex characters
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }
}