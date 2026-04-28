package com.patchlens.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubPrUrlParserTest {

    private GitHubPrUrlParser parser;

    @BeforeEach
    void setUp() {
        parser = new GitHubPrUrlParser();
    }

    @Test
    void shouldParseValidUrl() {
        var result = parser.parse("https://github.com/torvalds/linux/pull/1234");

        assertThat(result).isPresent();
        assertThat(result.get().owner()).isEqualTo("torvalds");
        assertThat(result.get().repo()).isEqualTo("linux");
        assertThat(result.get().pullNumber()).isEqualTo(1234);
    }

    @Test
    void shouldParseUrlWithQueryString() {
        var result = parser.parse("https://github.com/owner/repo/pull/99?tab=commits");

        assertThat(result).isPresent();
        assertThat(result.get().pullNumber()).isEqualTo(99);
    }

    @Test
    void shouldParseUrlWithTrailingSlash() {
        var result = parser.parse("https://github.com/owner/repo/pull/5/");

        assertThat(result).isPresent();
        assertThat(result.get().pullNumber()).isEqualTo(5);
    }

    @Test
    void shouldReturnEmptyForIssueUrl() {
        var result = parser.parse("https://github.com/owner/repo/issues/42");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyForRandomUrl() {
        var result = parser.parse("https://example.com/owner/repo/pull/1");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyForNullInput() {
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForBlankInput() {
        assertThat(parser.parse("   ")).isEmpty();
    }
}