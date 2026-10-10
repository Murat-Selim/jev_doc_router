package com.example.rag.core.ai.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.rag.core.ai.cache.CagCacheProvider.CachedContext;

@Component
@ConditionalOnProperty(name = "ai.routing.provider", havingValue = "rules", matchIfMissing = true)
public class RulesChatRoutingClient implements ChatRoutingClient {

    @Override
    public Decision decide(String question, CachedContext cache) {
        return new Decision(Strategy.CAG, "rules", null);
    }
}
