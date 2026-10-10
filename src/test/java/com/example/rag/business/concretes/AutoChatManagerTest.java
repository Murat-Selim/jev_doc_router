package com.example.rag.business.concretes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import com.example.rag.business.abstracts.CagService;
import com.example.rag.business.abstracts.RagService;
import com.example.rag.business.requests.chatRequest.ChatRequest;
import com.example.rag.business.responses.chatResponse.ChatResponse;
import com.example.rag.business.rules.CagBusinessRules;
import com.example.rag.core.ai.cache.CagCacheProvider;
import com.example.rag.core.ai.cache.CagCacheProvider.CachedContext;
import com.example.rag.core.ai.routing.ChatRoutingClient;
import com.example.rag.core.ai.routing.ChatRoutingClient.Decision;
import com.example.rag.core.ai.routing.ChatRoutingClient.Strategy;
import com.example.rag.core.exceptions.types.AiProviderException;
import com.example.rag.core.exceptions.types.BusinessException;

class AutoChatManagerTest {

    private final RagService rag = mock(RagService.class);
    private final CagService cag = mock(CagService.class);
    private final CagCacheProvider cache = mock(CagCacheProvider.class);
    private final ChatRoutingClient routing = mock(ChatRoutingClient.class);
    private final ChatRequest request = new ChatRequest("Compare the document sections.");
    private final CachedContext snapshot = new CachedContext("cache-1", "Annual leave is 14 days.");
    private final AutoChatManager manager = new AutoChatManager(
            rag, cag, cache, new CagBusinessRules(50000), routing, 8000, 0.6);

    @Test
    void jevCagDecisionUsesTheExactSnapshotEvenIfCacheChangesDuringRouting() {
        when(cache.getReadyCache()).thenReturn(snapshot, new CachedContext("cache-2", "new context"));
        when(routing.decide(request.question(), snapshot))
                .thenReturn(new Decision(Strategy.CAG, "jev", 0.9));
        when(cag.ask(request, snapshot)).thenReturn(new ChatResponse("14 days."));
        var response = manager.ask(request);
        assertEquals("14 days.", response.answer());
        assertEquals(Strategy.CAG, response.strategy());
        assertEquals("jev", response.decisionSource());
        assertEquals("JEV_SELECTED_CAG", response.reason());
        assertEquals(0.9, response.confidence());
        verify(cache, times(1)).getReadyCache();
        verify(cag).ask(request, snapshot);
        verifyNoInteractions(rag);
    }

    @Test
    void jevCanSelectRagWhileCagIsReady() {
        when(cache.getReadyCache()).thenReturn(snapshot);
        when(routing.decide(request.question(), snapshot))
                .thenReturn(new Decision(Strategy.RAG, "jev", 0.9));
        when(rag.ask(request)).thenReturn(new ChatResponse("Retrieved answer"));
        var response = manager.ask(request);
        assertEquals(Strategy.RAG, response.strategy());
        assertEquals("Retrieved answer", response.answer());
        assertEquals("JEV_SELECTED_RAG", response.reason());
        verifyNoInteractions(cag);
    }

    @Test
    void missingOrRefreshingCacheSkipsJevAndUsesRag() {
        when(rag.ask(request)).thenReturn(new ChatResponse("Retrieved answer"));
        var response = manager.ask(request);
        assertEquals(Strategy.RAG, response.strategy());
        assertEquals("CAG_UNAVAILABLE_OR_REFRESHING", response.reason());
        verifyNoInteractions(routing, cag);
    }

    @Test
    void oversizedContextSkipsJevAndUsesRag() {
        when(cache.getReadyCache()).thenReturn(new CachedContext("large", "a".repeat(32001)));
        when(rag.ask(request)).thenReturn(new ChatResponse("Retrieved answer"));
        assertEquals("CONTEXT_TOO_LARGE_FOR_AUTO_CAG", manager.ask(request).reason());
        verifyNoInteractions(routing, cag);
    }

    @Test
    void lowerCagHardLimitStillOverridesRoutingBudget() {
        var smallLimitManager = new AutoChatManager(rag, cag, cache,
                new CagBusinessRules(2), routing, 8000, 0.6);
        when(cache.getReadyCache()).thenReturn(snapshot);
        when(rag.ask(request)).thenReturn(new ChatResponse("Retrieved answer"));
        assertEquals("CONTEXT_INVALID_FOR_CAG", smallLimitManager.ask(request).reason());
        verifyNoInteractions(routing, cag);
    }

    @Test
    void lowConfidenceFallsBackToRagAndReportsRoutingConfidence() {
        when(cache.getReadyCache()).thenReturn(snapshot);
        when(routing.decide(request.question(), snapshot))
                .thenReturn(new Decision(Strategy.CAG, "jev", 0.2));
        when(rag.ask(request)).thenReturn(new ChatResponse("Retrieved answer"));
        var response = manager.ask(request);
        assertEquals(Strategy.RAG, response.strategy());
        assertEquals("LOW_ROUTING_CONFIDENCE", response.reason());
        assertEquals(0.2, response.confidence());
        verifyNoInteractions(cag);
    }

    @Test
    void jevFailureFallsBackButAnswerProviderFailureIsNotHiddenOrRetried() {
        when(cache.getReadyCache()).thenReturn(snapshot);
        when(routing.decide(request.question(), snapshot)).thenThrow(new AiProviderException("Unavailable"));
        when(rag.ask(request)).thenReturn(new ChatResponse("Retrieved answer"));
        assertEquals("JEV_UNAVAILABLE_OR_INVALID", manager.ask(request).reason());
        when(rag.ask(request)).thenThrow(new AiProviderException("Answer provider unavailable"));
        assertThrows(AiProviderException.class, () -> manager.ask(request));
        verifyNoInteractions(cag);
    }

    @Test
    void invalidQuestionDoesNotReadCacheOrCallAnyProvider() {
        assertThrows(BusinessException.class, () -> manager.ask(new ChatRequest(" ")));
        verifyNoInteractions(rag, cag, cache, routing);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new AutoChatManager(
                rag, cag, cache, new CagBusinessRules(50000), routing, 0, 0.6));
        assertThrows(IllegalArgumentException.class, () -> new AutoChatManager(
                rag, cag, cache, new CagBusinessRules(50000), routing, 8000, Double.NaN));
    }
}
