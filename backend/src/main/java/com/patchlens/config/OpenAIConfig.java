package com.patchlens.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenAIConfig {

    @Value("${openai.api-key:}")
    private String apiKey;

    @Bean(name = "openAiRestClient")
    public RestClient openAiRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }
}