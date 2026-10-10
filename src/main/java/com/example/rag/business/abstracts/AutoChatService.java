package com.example.rag.business.abstracts;

import com.example.rag.business.requests.chatRequest.ChatRequest;
import com.example.rag.business.responses.chatResponse.AutoChatResponse;

public interface AutoChatService {

    AutoChatResponse ask(ChatRequest request);
}
