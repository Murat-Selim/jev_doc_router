package com.example.rag.core.ai.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.example.rag.core.ai.model.DocumentChatClient;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InMemoryCagCacheProvider implements CagCacheProvider {

    private final DocumentChatClient chatClient;

    // ponytail: yerel snapshot tek instance içindir; OpenAI KV cache'i chatClient yönetir.
    private volatile CachedContext activeCache;
    private final AtomicInteger pendingRefreshes = new AtomicInteger();

    @Override
    public synchronized String createOrRefreshCache(String knowledgeContext) {
        CachedContext current = activeCache;
        if (current != null && current.context().equals(knowledgeContext)) {
            return current.id();
        }
        String cacheId = contextHash(knowledgeContext);
        chatClient.preload(knowledgeContext, cacheId);
        activeCache = new CachedContext(cacheId, knowledgeContext);
        return cacheId;
    }

    @Override
    public CachedContext getActiveCache() {
        return activeCache;
    }

    @Override
    public CachedContext getReadyCache() {
        if (pendingRefreshes.get() != 0) {
            return null;
        }
        CachedContext snapshot = activeCache;
        return pendingRefreshes.get() == 0 ? snapshot : null;
    }

    @Override
    public void refreshScheduled() {
        pendingRefreshes.incrementAndGet();
    }

    @Override
    public void refreshCompleted() {
        pendingRefreshes.decrementAndGet();
    }

    @Override
    public synchronized void invalidateCache() {
        activeCache = null;
    }

    private String contextHash(String context) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(context.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

}
