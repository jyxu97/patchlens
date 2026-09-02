package com.patchlens.ai;

import com.patchlens.ai.dto.ReviewResponse;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AI Service for issue detection.
 *
 * Wraps the "detect findings" step of the multi-step review pipeline.
 * Prompt files are versioned in src/main/resources/prompts/ so that
 * evaluation runs can record which prompt version produced each result.
 *
 * Bean is created by {@link com.patchlens.config.LangChain4jConfig}
 * only when ai.mode=openai.
 */
public interface ReviewAiService {

    @SystemMessage(fromResource = "prompts/review-system-v1.txt")
    @UserMessage(fromResource = "prompts/review-user-v1.txt")
    ReviewResponse review(
            @V("prSummary")         String prSummary,
            @V("diff")              String diff,
            @V("context")           String repositoryContext,
            @V("constraints")       String constraints
    );
}
