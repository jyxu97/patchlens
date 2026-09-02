package com.patchlens.ai;

import com.patchlens.ai.dto.PatchProposal;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AI Service for patch generation.
 *
 * Given a confirmed finding and the target file's diff, generates a minimal
 * unified diff that fixes the issue without touching unrelated code.
 *
 * Bean is created by {@link com.patchlens.config.LangChain4jConfig}
 * only when ai.mode=openai.
 */
public interface PatchAiService {

    @SystemMessage(fromResource = "prompts/patch-system-v1.txt")
    @UserMessage(fromResource = "prompts/patch-user-v1.txt")
    PatchProposal generatePatch(
            @V("finding")     String finding,
            @V("targetCode")  String targetCode,
            @V("context")     String context,
            @V("constraints") String constraints
    );
}
