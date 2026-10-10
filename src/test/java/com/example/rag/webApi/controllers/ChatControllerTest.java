package com.example.rag.webApi.controllers;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.rag.business.abstracts.RagService;
import com.example.rag.business.concretes.AutoChatManager;
import com.example.rag.business.concretes.CagManager;
import com.example.rag.business.rules.CagBusinessRules;
import com.example.rag.core.ai.cache.InMemoryCagCacheProvider;
import com.example.rag.core.ai.model.DocumentChatClient;
import com.example.rag.core.ai.prompt.CagPromptBuilder;
import com.example.rag.core.ai.routing.RulesChatRoutingClient;
import com.example.rag.core.exceptions.handlers.GlobalExceptionHandler;
import com.example.rag.core.exceptions.types.AiProviderException;

class ChatControllerTest {

    private final DocumentChatClient chat = mock(DocumentChatClient.class);
    private final InMemoryCagCacheProvider cache = new InMemoryCagCacheProvider(chat);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var cag = new CagManager(cache, new CagPromptBuilder(), new CagBusinessRules(50000), chat);
        var rag = mock(RagService.class);
        var auto = new AutoChatManager(rag, cag, cache, new CagBusinessRules(50000),
                new RulesChatRoutingClient(), 8000, 0.6);
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(auto))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void autoEndpointReportsActualStrategy() throws Exception {
        String key = cache.createOrRefreshCache("Annual leave is 14 days.");
        when(chat.answer("Question:\nHow long is annual leave?", "Annual leave is 14 days.", key))
                .thenReturn("14 days.");
        mvc.perform(post("/api/chat/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How long is annual leave?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("14 days."))
                .andExpect(jsonPath("$.strategy").value("CAG"))
                .andExpect(jsonPath("$.decisionSource").value("rules"))
                .andExpect(jsonPath("$.reason").value("READY_COMPACT_CONTEXT"));
    }

    @Test
    void autoEndpointRejectsBlankQuestionBeforeAnyModelCall() throws Exception {
        mvc.perform(post("/api/chat/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chat);
    }

    @Test
    void modelFailureReturnsBadGateway() throws Exception {
        cache.createOrRefreshCache("context");
        when(chat.answer(anyString(), anyString(), anyString()))
                .thenThrow(new AiProviderException("AI provider request failed."));
        mvc.perform(post("/api/chat/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Question\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("AI provider request failed."));
    }

}
