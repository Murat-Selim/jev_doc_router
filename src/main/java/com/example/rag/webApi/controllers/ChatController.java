package com.example.rag.webApi.controllers;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import com.example.rag.business.abstracts.AutoChatService;
import com.example.rag.business.requests.chatRequest.ChatRequest;
import com.example.rag.business.responses.chatResponse.AutoChatResponse;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Document-based question answering")
public class ChatController {

    private final AutoChatService autoChatService;

    @PostMapping("/auto")
    @Operation(summary = "Ask with automatic RAG/CAG routing",
            description = "Selects RAG or CAG using context readiness, size and optional Jev decisions; "
                    + "returns the selected strategy, decision source, reason and routing confidence")
    public ResponseEntity<AutoChatResponse> askAutomatically(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(autoChatService.ask(request));
    }

}
