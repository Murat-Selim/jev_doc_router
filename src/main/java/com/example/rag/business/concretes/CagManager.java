package com.example.rag.business.concretes;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import com.example.rag.business.abstracts.CagService;
import com.example.rag.business.requests.chatRequest.ChatRequest;
import com.example.rag.business.responses.chatResponse.ChatResponse;
import com.example.rag.business.rules.CagBusinessRules;
import com.example.rag.core.ai.cache.CagCacheProvider;
import com.example.rag.core.ai.model.DocumentChatClient;
import com.example.rag.core.ai.prompt.CagPromptBuilder;

@Service
@RequiredArgsConstructor
public class CagManager implements CagService {

    private final CagCacheProvider cagCacheProvider;
    private final CagPromptBuilder cagPromptBuilder;
    private final CagBusinessRules rules;
    private final DocumentChatClient chatClient;

    @Override
    public ChatResponse ask(ChatRequest request) {
        rules.questionMustBeValid(request.question());

        return ask(request, cagCacheProvider.getActiveCache());
    }

    @Override
    public ChatResponse ask(ChatRequest request, CagCacheProvider.CachedContext cache) {
        rules.questionMustBeValid(request.question());
        rules.activeCacheMustExist(cache == null ? null : cache.id());

        rules.contextMustBeValid(cache.context());

        String prompt = cagPromptBuilder.build(request.question());

        return new ChatResponse(chatClient.answer(prompt, cache.context(), cache.id()));
    }

}
