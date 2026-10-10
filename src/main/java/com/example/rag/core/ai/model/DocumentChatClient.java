package com.example.rag.core.ai.model;

public interface DocumentChatClient {

    void preload(String knowledgeContext, String cacheKey);

    String answer(String questionPrompt, String knowledgeContext, String cacheKey);

}
