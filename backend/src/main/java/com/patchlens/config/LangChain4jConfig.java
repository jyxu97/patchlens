package com.patchlens.config;

import com.patchlens.ai.PatchAiService;
import com.patchlens.ai.RepairAiService;
import com.patchlens.ai.ReviewAiService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

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

    /**
     * LangChain4j pgvector embedding store.
     *
     * Stores embeddings in the {@code lc4j_embeddings} table (separate from the
     * domain-specific {@code context_chunk} table so both schemas can coexist).
     * {@code createTable=true} auto-provisions the table on first start.
     *
     * Metadata stored per segment:
     *   repositoryOwner — GitHub repo owner
     *   repositoryName  — GitHub repo name
     *   filePath        — source file path within the repo
     *   fileType        — classified type (SOURCE, DOC, CONFIG, …)
     *
     * Dimension (1536) matches text-embedding-3-small output.
     * Use EmbeddingStore<TextSegment> as the injection type to avoid binding callers
     * to the concrete PgVectorEmbeddingStore class.
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(DataSource dataSource, EmbeddingModel embeddingModel) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("lc4j_embeddings")
                .dimension(embeddingModel.dimension())
                .createTable(true)
                .build();
    }
}
