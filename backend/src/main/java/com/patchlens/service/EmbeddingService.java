package com.patchlens.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Service
public class EmbeddingService {

    private static final int EMBEDDING_DIMENSION = 1536;

    private final RestClient restClient;
    private final String embeddingModel;
    private final String aiMode;

    public EmbeddingService(
            @Qualifier("openAiRestClient") RestClient restClient,
            @Value("${openai.embedding-model:text-embedding-3-small}") String embeddingModel,
            @Value("${ai.mode:mock}") String aiMode) {
        this.restClient = restClient;
        this.embeddingModel = embeddingModel;
        this.aiMode = aiMode;
    }

    /**
     * Returns a 1536-dimension embedding vector for the given text.
     * In mock mode, returns a zero vector without calling OpenAI.
     */
    public float[] embed(String text) {
        if ("mock".equalsIgnoreCase(aiMode)) {
            // Zero vector: no meaningful similarity in mock mode,
            // but allows the full indexing and retrieval pipeline to run
            return new float[EMBEDDING_DIMENSION];
        }
        return callEmbeddingsApi(text);
    }

    private float[] callEmbeddingsApi(String text) {
        Map<String, Object> requestBody = Map.of(
                "model", embeddingModel,
                "input", text
        );

        JsonNode response = restClient.post()
                .uri("/v1/embeddings")
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        // Response structure: { "data": [{ "embedding": [0.1, 0.2, ...] }] }
        JsonNode embeddingArray = response.get("data").get(0).get("embedding");

        float[] result = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            result[i] = (float) embeddingArray.get(i).asDouble();
        }
        return result;
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