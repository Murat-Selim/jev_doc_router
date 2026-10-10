package com.example.rag.business.concretes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.rag.business.abstracts.EmbeddingService;
import com.example.rag.dataAccess.abstracts.DocumentChunkRepository;
import com.example.rag.core.ai.model.DocumentChatClient;
import com.example.rag.core.ai.prompt.CagPromptBuilder;

@ExtendWith(MockitoExtension.class)
class RagManagerTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private com.example.rag.business.rules.RagBusinessRules rules;

    @Mock
    private DocumentChunkRepository repository;

    @Mock
    private DocumentChatClient chatClient;

    @Spy
    private CagPromptBuilder promptBuilder = new CagPromptBuilder();

    @InjectMocks
    private RagManager ragManager;

    private DocumentChunkRepository.SimilarChunk chunk(String content) {
        return new DocumentChunkRepository.SimilarChunk() {
            @Override
            public Long getId() {
                return 1L;
            }

            @Override
            public String getDocumentName() {
                return "test.pdf";
            }

            @Override
            public String getContent() {
                return content;
            }

            @Override
            public Double getDistance() {
                return 0.1;
            }
        };
    }

    @Test
    void askSendsRetrievedContextAndQuestionToModel() {
        when(embeddingService.embed(anyString())).thenReturn("[0.1,0.2]");
        when(repository.findSimilarChunks(anyString(), anyInt()))
                .thenReturn(List.of(chunk("Yıllık izin 14 gündür.")));
        when(chatClient.answer("Question:\nYıllık izin?", "Yıllık izin 14 gündür.", null))
                .thenReturn("Yıllık izin 14 gündür.");

        var response = ragManager.ask(new com.example.rag.business.requests.chatRequest.ChatRequest("Yıllık izin?"));

        assertTrue(response.answer().contains("Yıllık izin 14 gündür."));
        verify(repository).findSimilarChunks("[0.1,0.2]", 3);
        verify(chatClient).answer("Question:\nYıllık izin?", "Yıllık izin 14 gündür.", null);
    }

    @Test
    void askThrowsWhenNoChunksFound() {
        when(embeddingService.embed(anyString())).thenReturn("[0.1,0.2]");
        when(repository.findSimilarChunks(anyString(), anyInt())).thenReturn(List.of());
        doThrow(new com.example.rag.core.exceptions.types.BusinessException("No document content available."))
                .when(rules).contextMustExist(any());

        assertThrows(com.example.rag.core.exceptions.types.BusinessException.class,
                () -> ragManager.ask(new com.example.rag.business.requests.chatRequest.ChatRequest("Soru")));
    }

}
