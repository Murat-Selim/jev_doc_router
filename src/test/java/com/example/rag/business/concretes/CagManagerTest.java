package com.example.rag.business.concretes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.rag.business.requests.chatRequest.ChatRequest;
import com.example.rag.business.rules.CagBusinessRules;
import com.example.rag.core.ai.cache.CagCacheProvider;
import com.example.rag.core.ai.model.DocumentChatClient;
import com.example.rag.core.ai.prompt.CagPromptBuilder;
import com.example.rag.core.exceptions.types.BusinessException;

class CagManagerTest {

    private final CagCacheProvider cache = mock(CagCacheProvider.class);
    private final DocumentChatClient chat = mock(DocumentChatClient.class);
    private final CagManager manager = new CagManager(
            cache, new CagPromptBuilder(), new CagBusinessRules(50000), chat);

    @Test
    void askPassesQuestionAndOneCacheSnapshotToModel() {
        when(cache.getActiveCache()).thenReturn(
                new CagCacheProvider.CachedContext("cache-1", "Yıllık izin 14 gündür."));
        when(chat.answer("Question:\nİzin kaç gün?", "Yıllık izin 14 gündür.", "cache-1"))
                .thenReturn("14 gün.");
        when(chat.answer("Question:\nMaaş ne kadar?", "Yıllık izin 14 gündür.", "cache-1"))
                .thenReturn("Yüklenen belgelerde bu bilgi bulunamadı.");

        assertEquals("14 gün.", manager.ask(new ChatRequest("İzin kaç gün?")).answer());
        assertEquals("Yüklenen belgelerde bu bilgi bulunamadı.",
                manager.ask(new ChatRequest("Maaş ne kadar?")).answer());
        verify(chat).answer("Question:\nİzin kaç gün?", "Yıllık izin 14 gündür.", "cache-1");
    }

    @Test
    void rejectsBlankQuestionBeforeReadingCacheOrCallingModel() {
        assertThrows(BusinessException.class, () -> manager.ask(new ChatRequest(" ")));
        assertThrows(BusinessException.class, () -> manager.ask(new ChatRequest(null)));
        verifyNoInteractions(cache, chat);
    }

    @Test
    void rejectsMissingCacheWithoutCallingModel() {
        assertThrows(BusinessException.class, () -> manager.ask(new ChatRequest("Soru")));
        verifyNoInteractions(chat);
    }

    @Test
    void rejectsInvalidCachedContextWithoutCallingModel() {
        when(cache.getActiveCache()).thenReturn(new CagCacheProvider.CachedContext("cache-1", " "));
        assertThrows(BusinessException.class, () -> manager.ask(new ChatRequest("Soru")));
        when(cache.getActiveCache()).thenReturn(
                new CagCacheProvider.CachedContext("cache-2", "a".repeat(200001)));
        assertThrows(BusinessException.class, () -> manager.ask(new ChatRequest("Soru")));
        verifyNoInteractions(chat);
    }

}
