package com.patchlens.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deserializes the GitHub pull_request webhook event JSON body.
 * Only the fields we need are mapped; everything else is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPrPayload(
        String action,
        @JsonProperty("pull_request") PullRequest pullRequest,
        Repository repository
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(
            int number,
            @JsonProperty("html_url") String htmlUrl,
            String title
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
            String name,
            Owner owner
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(
            String login
    ) {}
}
