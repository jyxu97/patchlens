package com.patchlens.config;

import com.patchlens.ai.PatchAiService;
import com.patchlens.ai.RepairAiService;
import com.patchlens.ai.ReviewAiService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
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

    @Value("${openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    /** Hard per-request timeout: a stalled model call can never block a worker indefinitely. */
    @Value("${openai.timeout-seconds:60}")
    private int timeoutSeconds;

    /** Bounded retries for transient failures (timeouts, 429, 5xx); 4xx errors are not retried. */
    @Value("${openai.max-retries:2}")
    private int maxRetries;

    @Value("${openai.temperature:0.1}")
    private double temperature;

    @Value("${openai.max-tokens:4000}")
    private int maxTokens;

    @Value("${openai.embedding-model:text-embedding-3-small}")
    private String embeddingModelName;

    /**
     * Shared chat model used by ReviewAiService, PatchAiService, and RepairAiService.
     * Low temperature (0.1) is intentional: code review requires precision, not creativity.
     */
    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .logRequests(false)      // prompts contain PR source code — never log them
                .logResponses(false)
                .modelName(chatModelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * Embedding model used by the pgvector retrieval adapter.
     * Dimension matches the 1536-float schema already in the database.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .modelName(embeddingModelName)
                .build();
    }

    /**
     * Issue-detection AI service — wraps the multi-step review prompt
     * (review-system-v1.txt + review-user-v1.txt) and returns structured findings.
     */
    @Bean
    public ReviewAiService reviewAiService(ChatModel chatModel) {
        return AiServices.builder(ReviewAiService.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * Patch generation AI service — given a confirmed finding and the target
     * file's diff, generates a minimal unified diff fix.
     */
    @Bean
    public PatchAiService patchAiService(ChatModel chatModel) {
        return AiServices.builder(PatchAiService.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * Repair AI service — invoked when a generated patch fails sandbox
     * validation; returns a replacement patch that addresses only the failure.
     * Repair attempts are bounded by PatchGenerationService.MAX_REPAIR_ATTEMPTS.
     */
    @Bean
    public RepairAiService repairAiService(ChatModel chatModel) {
        return AiServices.builder(RepairAiService.class)
                .chatModel(chatModel)
                .build();
    }

}
