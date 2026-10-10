package com.example.rag.core.ai.routing;

import com.example.rag.core.ai.cache.CagCacheProvider.CachedContext;

public interface ChatRoutingClient {

    Decision decide(String question, CachedContext cache);

    enum Strategy { RAG, CAG }

    record Decision(Strategy strategy, String source, Double confidence) {}
}
