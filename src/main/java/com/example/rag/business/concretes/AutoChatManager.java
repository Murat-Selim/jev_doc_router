package com.example.rag.business.concretes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.example.rag.business.abstracts.AutoChatService;
import com.example.rag.business.abstracts.CagService;
import com.example.rag.business.abstracts.RagService;
import com.example.rag.business.requests.chatRequest.ChatRequest;
import com.example.rag.business.responses.chatResponse.AutoChatResponse;
import com.example.rag.business.rules.CagBusinessRules;
import com.example.rag.core.ai.cache.CagCacheProvider;
import com.example.rag.core.ai.routing.ChatRoutingClient;
import com.example.rag.core.ai.routing.ChatRoutingClient.Strategy;
import com.example.rag.core.exceptions.types.AiProviderException;
import com.example.rag.core.exceptions.types.BusinessException;

@Slf4j
@Service
public class AutoChatManager implements AutoChatService {

    private final RagService ragService;
    private final CagService cagService;
    private final CagCacheProvider cacheProvider;
    private final CagBusinessRules rules;
    private final ChatRoutingClient routingClient;
    private final int maxCagContextTokens;
    private final double minConfidence;

    public AutoChatManager(RagService ragService, CagService cagService,
            CagCacheProvider cacheProvider, CagBusinessRules rules, ChatRoutingClient routingClient,
            @Value("${ai.routing.max-cag-context-tokens:8000}") int maxCagContextTokens,
            @Value("${ai.routing.min-confidence:0.6}") double minConfidence) {
        if (maxCagContextTokens <= 0 || !Double.isFinite(minConfidence)
                || minConfidence < 0 || minConfidence > 1) {
            throw new IllegalArgumentException("Positive routing token limit and confidence in [0,1] are required.");
        }
        this.ragService = ragService;
        this.cagService = cagService;
        this.cacheProvider = cacheProvider;
        this.rules = rules;
        this.routingClient = routingClient;
        this.maxCagContextTokens = maxCagContextTokens;
        this.minConfidence = minConfidence;
    }

    @Override
    public AutoChatResponse ask(ChatRequest request) {
        rules.questionMustBeValid(request.question());
        var cache = cacheProvider.getReadyCache();
        if (cache == null) {
            return rag(request, "rules", "CAG_UNAVAILABLE_OR_REFRESHING", null);
        }
        if (cache.estimatedTokens() > maxCagContextTokens) {
            return rag(request, "rules", "CONTEXT_TOO_LARGE_FOR_AUTO_CAG", null);
        }
        try {
            rules.contextMustBeValid(cache.context());
        } catch (BusinessException ex) {
            return rag(request, "rules", "CONTEXT_INVALID_FOR_CAG", null);
        }
        ChatRoutingClient.Decision decision;
        try {
            decision = routingClient.decide(request.question(), cache);
        } catch (AiProviderException ex) {
            return rag(request, "fallback", "JEV_UNAVAILABLE_OR_INVALID", null);
        }
        if (decision.confidence() != null && decision.confidence() < minConfidence) {
            log.info("Low routing confidence: {} leaned {} ({} < {}); falling back to RAG.",
                    decision.source(), decision.strategy(), decision.confidence(), minConfidence);
            return rag(request, decision.source(), "LOW_ROUTING_CONFIDENCE", decision.confidence());
        }
        if (decision.strategy() == Strategy.RAG) {
            return rag(request, decision.source(), "JEV_SELECTED_RAG", decision.confidence());
        }
        // Seçimde okunan snapshot kullanılır; aradaki refresh başka bir bağlamı seçtirmemeli.
        var response = cagService.ask(request, cache);
        return new AutoChatResponse(response.answer(), Strategy.CAG, decision.source(),
                "jev".equals(decision.source()) ? "JEV_SELECTED_CAG" : "READY_COMPACT_CONTEXT",
                decision.confidence());
    }

    private AutoChatResponse rag(ChatRequest request, String source, String reason, Double confidence) {
        return new AutoChatResponse(ragService.ask(request).answer(), Strategy.RAG, source, reason, confidence);
    }
}
