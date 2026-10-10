package com.example.rag.core.ai.cache;

public interface CagCacheProvider {

    String createOrRefreshCache(String knowledgeContext);

    CachedContext getActiveCache();

    CachedContext getReadyCache();

    void refreshScheduled();

    void refreshCompleted();

    void invalidateCache();

    record CachedContext(String id, String context) {
        public long estimatedTokens() {
            return (context.length() + 3L) / 4L;
        }
    }

}
