package com.patchlens.model;

public record PullRequestMetadata(
        String owner,
        String repo,
        int pullNumber,
        String title,
        String body,
        String url
) {}