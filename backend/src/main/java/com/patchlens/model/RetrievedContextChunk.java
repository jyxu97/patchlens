package com.patchlens.model;

/**
 * A context chunk retrieved from pgvector during RAG retrieval.
 * Returned in API responses so callers can see which repository files
 * influenced the AI-generated review.
 */
public record RetrievedContextChunk(
        String filePath,
        String content,          // full chunk text, used for the OpenAI prompt
        double similarityScore   // cosine similarity: 0.0 (unrelated) → 1.0 (identical)
) {
    /** Short preview for display; first 200 chars of the chunk content. */
    public String contentPreview() {
        return content.length() > 200 ? content.substring(0, 200) + "…" : content;
    }
}
