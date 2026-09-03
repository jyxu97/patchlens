package com.patchlens.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Generates vector embeddings for text segments.
 *
 * Routing by mode:
 *   ai.mode=openai → LangChain4j {@link EmbeddingModel} (provider-agnostic; currently backed by
 *                    OpenAiEmbeddingModel configured in {@link com.patchlens.config.LangChain4jConfig})
 *   ai.mode=mock   → zero vector; pgvector similarity always 0, but the full indexing/retrieval
 *                    pipeline can still run end-to-end without API calls
 *
 * The LangChain4j abstraction lets us swap embedding providers (e.g. Cohere, local Ollama) by
 * changing a single @Bean in LangChain4jConfig without touching this class.
 */
@Service
public class EmbeddingService {

    static final int EMBEDDING_DIMENSION = 1536;

    private final Optional<EmbeddingModel> embeddingModel;
    private final String aiMode;

    public EmbeddingService(
            Optional<EmbeddingModel> embeddingModel,
            @Value("${ai.mode:mock}") String aiMode) {
        this.embeddingModel = embeddingModel;
        this.aiMode = aiMode;
    }

    /**
     * Returns a 1536-dimension embedding vector for the given text.
     *
     * In openai mode, delegates to LangChain4j {@link EmbeddingModel} for provider abstraction.
     * In mock mode, returns a zero vector so the pipeline can run without API calls.
     */
    public float[] embed(String text) {
        if ("mock".equalsIgnoreCase(aiMode)) {
            return new float[EMBEDDING_DIMENSION];
        }
        Response<Embedding> response = embeddingModel.get().embed(text);
        return response.content().vector();
    }

    /** Converts a float[] to the PostgreSQL vector string format "[0.1,0.2,...]" */
    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
