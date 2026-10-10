package com.example.rag.business.responses.chatResponse;

import com.example.rag.core.ai.routing.ChatRoutingClient.Strategy;

public record AutoChatResponse(
        String answer,
        Strategy strategy,
        String decisionSource,
        String reason,
        Double confidence
) {}
