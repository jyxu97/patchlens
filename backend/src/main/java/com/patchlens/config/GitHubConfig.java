package com.patchlens.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GitHubConfig {

    // Injected from environment variable GITHUB_TOKEN, empty string if not set
    @Value("${github.token:}")
    private String githubToken;

    @Bean(name = "githubRestClient")
    public RestClient githubRestClient() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                // GitHub requires this header to use the REST API v3
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");

        // If a token is provided, attach it to every request to get higher rate limits
        // Without token: 60 requests/hour. With token: 5000 requests/hour.
        if (!githubToken.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + githubToken);
        }

        return builder.build();
    }
}