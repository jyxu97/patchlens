package com.patchlens.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenAIConfig {

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.timeout-seconds:60}")
    private int timeoutSeconds = 60;

    @Bean(name = "openAiRestClient")
    public RestClient openAiRestClient() {
        return RestClient.builder()
                .requestFactory(HttpTimeouts.factory(Duration.ofSeconds(10), Duration.ofSeconds(timeoutSeconds)))
                .baseUrl("https://api.openai.com")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }
}