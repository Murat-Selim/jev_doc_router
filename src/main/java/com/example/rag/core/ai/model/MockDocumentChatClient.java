package com.example.rag.core.ai.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockDocumentChatClient implements DocumentChatClient {

    @Override
    public void preload(String knowledgeContext, String cacheKey) {
        // Mock modunda ağ veya model çağrısı yapılmaz.
    }

    @Override
    public String answer(String questionPrompt, String knowledgeContext, String cacheKey) {
        StringBuilder answer = new StringBuilder("[Mock answer; no LLM called]\n\n")
                .append(questionPrompt).append("\n\n");
        for (String chunk : knowledgeContext.split("\\R\\R+")) {
            if (!chunk.isBlank()) {
                answer.append("- ").append(chunk, 0, Math.min(200, chunk.length())).append("...\n");
            }
        }
        return answer.toString().strip();
    }

}
