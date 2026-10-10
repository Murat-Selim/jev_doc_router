package com.example.rag.business.abstracts;

import com.example.rag.business.requests.chatRequest.ChatRequest;
import com.example.rag.business.responses.chatResponse.ChatResponse;
import com.example.rag.core.ai.cache.CagCacheProvider.CachedContext;

public interface CagService {

    ChatResponse ask(ChatRequest request);

    ChatResponse ask(ChatRequest request, CachedContext cache);

}
