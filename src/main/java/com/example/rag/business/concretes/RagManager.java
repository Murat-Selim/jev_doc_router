package com.example.rag.business.concretes;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import com.example.rag.business.abstracts.EmbeddingService;
import com.example.rag.business.abstracts.RagService;
import com.example.rag.business.requests.chatRequest.ChatRequest;
import com.example.rag.business.responses.chatResponse.ChatResponse;
import com.example.rag.business.rules.RagBusinessRules;
import com.example.rag.core.ai.model.DocumentChatClient;
import com.example.rag.core.ai.prompt.CagPromptBuilder;
import com.example.rag.dataAccess.abstracts.DocumentChunkRepository;

@Service
@RequiredArgsConstructor
public class RagManager implements RagService {

    private static final int TOP_K = 3;

    private final EmbeddingService embeddingService;
    private final RagBusinessRules rules;
    private final DocumentChunkRepository repository;
    private final DocumentChatClient chatClient;
    private final CagPromptBuilder promptBuilder;

    @Override
    public ChatResponse ask(ChatRequest request) {
        rules.questionMustBeValid(request.question());

        String questionEmbedding = embeddingService.embed(request.question());
        List<DocumentChunkRepository.SimilarChunk> relevantChunks =
                repository.findSimilarChunks(questionEmbedding, TOP_K);
        rules.contextMustExist(relevantChunks);

        String context = buildContext(relevantChunks);
        String prompt = promptBuilder.build(request.question());

        return new ChatResponse(chatClient.answer(prompt, context, null));
    }

    private String buildContext(List<DocumentChunkRepository.SimilarChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (DocumentChunkRepository.SimilarChunk chunk : chunks) {
            sb.append(chunk.getContent()).append("\n\n");
        }
        return sb.toString().strip();
    }

}
