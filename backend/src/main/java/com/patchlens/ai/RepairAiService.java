package com.patchlens.ai;

import com.patchlens.ai.dto.PatchProposal;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AI Service for bounded patch repair.
 *
 * Invoked when a generated patch fails sandbox validation (compile, static
 * analysis, or tests). Receives the error summary and returns a replacement
 * patch that addresses only the reported failure.
 *
 * Repair is capped at {@code MAX_REPAIR_ATTEMPTS} in
 * {@link com.patchlens.service.PatchGenerationService}.
 *
 * Bean is created by {@link com.patchlens.config.LangChain4jConfig}
 * only when ai.mode=openai.
 */
public interface RepairAiService {

    @SystemMessage(fromResource = "prompts/repair-system-v1.txt")
    @UserMessage(fromResource = "prompts/repair-user-v1.txt")
    PatchProposal repair(
            @V("finding")           String finding,
            @V("previousPatch")     String previousPatch,
            @V("validationFailure") String validationFailure,
            @V("context")           String context
    );
}
