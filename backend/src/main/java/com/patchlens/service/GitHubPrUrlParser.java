package com.patchlens.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a GitHub pull request URL into its constituent parts.
 * Expected format: https://github.com/{owner}/{repo}/pull/{number}
 */
@Component
public class GitHubPrUrlParser {

    // Capture groups: (1) owner  (2) repo  (3) PR number
    // The trailing .* allows query strings like ?tab=commits
    private static final Pattern PR_URL_PATTERN =
            Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/pull/(\\d+).*");

    public record ParsedPrUrl(String owner, String repo, int pullNumber) {}

    /**
     * Parses the given URL and returns the result, or empty if the URL is invalid.
     */
    public Optional<ParsedPrUrl> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = PR_URL_PATTERN.matcher(url.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedPrUrl(
                matcher.group(1),
                matcher.group(2),
                Integer.parseInt(matcher.group(3))
        ));
    }
}