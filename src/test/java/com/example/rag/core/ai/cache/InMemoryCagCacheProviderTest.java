package com.example.rag.core.ai.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import com.example.rag.core.ai.model.DocumentChatClient;
import com.example.rag.core.exceptions.types.AiProviderException;

class InMemoryCagCacheProviderTest {

    private final DocumentChatClient chat = mock(DocumentChatClient.class);
    private final InMemoryCagCacheProvider cache = new InMemoryCagCacheProvider(chat);

    @Test
    void unchangedContextReusesStableKeyAndDoesNotPrewarmAgain() {
        String key = cache.createOrRefreshCache("same context");
        assertEquals(key, cache.createOrRefreshCache("same context"));
        verify(chat, times(1)).preload("same context", key);
        assertEquals(key, new InMemoryCagCacheProvider(chat).createOrRefreshCache("same context"));
    }

    @Test
    void refreshAndInvalidationDoNotChangeAnAlreadyReadSnapshot() {
        String oldId = cache.createOrRefreshCache("old context");
        var snapshot = cache.getActiveCache();
        String newId = cache.createOrRefreshCache("new context");
        assertNotEquals(oldId, newId);
        assertEquals(oldId, snapshot.id());
        assertEquals("old context", snapshot.context());
        assertEquals("new context", cache.getActiveCache().context());
        cache.invalidateCache();
        assertNull(cache.getActiveCache());
        assertEquals("old context", snapshot.context());
    }

    @Test
    void failedPrewarmNeverPublishesAnUnpreparedSnapshot() {
        cache.createOrRefreshCache("old context");
        var previous = cache.getActiveCache();
        doThrow(new AiProviderException("prewarm failed")).when(chat).preload(eq("new context"), anyString());
        assertThrows(AiProviderException.class, () -> cache.createOrRefreshCache("new context"));
        assertSame(previous, cache.getActiveCache());
    }

    @Test
    void readyCacheWaitsForEveryScheduledRefreshWithoutRemovingManualSnapshot() {
        cache.createOrRefreshCache("old context");
        var previous = cache.getActiveCache();
        cache.refreshScheduled();
        cache.refreshScheduled();
        assertSame(previous, cache.getActiveCache());
        assertNull(cache.getReadyCache());
        cache.createOrRefreshCache("new context");
        cache.refreshCompleted();
        assertNull(cache.getReadyCache());
        cache.refreshCompleted();
        assertEquals("new context", cache.getReadyCache().context());
    }

}
