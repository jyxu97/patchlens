package com.patchlens.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manually wires LangChain4j beans without relying on the Spring Boot starter,
 * keeping compatibility with Spring Boot 4 / Spring Framework 7.
 *
 * Only activated when ai.mode=openai. In mock mode these beans are absent
 * and the existing OpenAIService mock path handles all LLM calls.
 */
@Configuration
@ConditionalOnProperty(name = "ai.mode", havingValue = "openai")
public class LangChain4jConfig {

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String chatModelName;

    @Value("${openai.embedding-model:text-embedding-3-small}")
    private String embeddingModelName;

    /**
     * Shared chat model used by ReviewAiService, PatchAiService, and RepairAiService.
     * Low temperature (0.1) is intentional: code review requires precision, not creativity.
     */
    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(chatModelName)
                .temperature(0.1)
                .maxTokens(4000)
                .build();
    }

    /**
     * Embedding model used by the pgvector retrieval adapter.
     * Dimension matches the 1536-float schema already in the database.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .build();
    }
}
