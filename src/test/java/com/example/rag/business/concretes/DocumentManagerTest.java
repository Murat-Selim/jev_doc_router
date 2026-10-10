package com.example.rag.business.concretes;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.rag.business.abstracts.ChunkingService;
import com.example.rag.business.abstracts.EmbeddingService;
import com.example.rag.business.rules.CagBusinessRules;
import com.example.rag.business.rules.DocumentBusinessRules;
import com.example.rag.core.ai.cache.InMemoryCagCacheProvider;
import com.example.rag.core.ai.model.DocumentChatClient;
import com.example.rag.core.exceptions.types.AiProviderException;
import com.example.rag.dataAccess.abstracts.DocumentChunkRepository;
import com.example.rag.entities.concretes.DocumentChunk;

class DocumentManagerTest {

    private final ChunkingService chunking = mock(ChunkingService.class);
    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final DocumentChunkRepository repository = mock(DocumentChunkRepository.class);
    private final DocumentChatClient chat = mock(DocumentChatClient.class);
    private final InMemoryCagCacheProvider cache = new InMemoryCagCacheProvider(chat);
    private final TestTransactionManager transactions = new TestTransactionManager();
    private final List<Runnable> pendingRefreshes = new ArrayList<>();
    private final DocumentManager manager = new DocumentManager(new DocumentBusinessRules(),
            chunking, embedding, repository, cache, new CagBusinessRules(50000), transactions,
            pendingRefreshes::add);

    @Test
    void uploadStoresChunksAndRefreshesOnlyAfterCommit() throws Exception {
        var file = prepareUpload();
        when(repository.findAll(Sort.by("id"))).thenAnswer(call -> {
            assertTrue(transactions.commits.get() > 0, "Cache must read only after upload commit");
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return List.of(new DocumentChunk("test.pdf", "chunk"));
        });

        new TransactionTemplate(transactions).execute(status -> {
            var response = manager.upload(file);
            assertEquals(1, response.chunks());
            assertNull(cache.getActiveCache());
            verify(repository, never()).findAll(any(Sort.class));
            return response;
        });

        verify(repository).insertChunk("test.pdf", "chunk", "[0.1]");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1, pendingRefreshes.size());
        assertNull(cache.getActiveCache());
        pendingRefreshes.get(0).run();
        assertEquals("chunk", cache.getActiveCache().context());
        verify(repository, never()).save(any());
    }

    @Test
    void rollbackPreservesPreviouslyCommittedCache() throws Exception {
        var file = prepareUpload();
        cache.createOrRefreshCache("previous content");
        var previous = cache.getActiveCache();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            manager.upload(file);
            status.setRollbackOnly();
        });
        assertSame(previous, cache.getActiveCache());
        assertSame(previous, cache.getReadyCache());
        assertTrue(pendingRefreshes.isEmpty());
        verify(repository, never()).findAll(any(Sort.class));
    }

    @Test
    void failedCommitDoesNotPublishUncommittedContext() throws Exception {
        var file = prepareUpload();
        cache.createOrRefreshCache("previous content");
        var previous = cache.getActiveCache();
        transactions.failCommit = true;
        assertThrows(TransactionSystemException.class,
                () -> new TransactionTemplate(transactions).execute(status -> manager.upload(file)));
        assertSame(previous, cache.getActiveCache());
        assertTrue(pendingRefreshes.isEmpty());
        verify(repository, never()).findAll(any(Sort.class));
    }

    @Test
    void oversizedContextKeepsUploadSuccessfulAndDisablesCag() throws Exception {
        var file = prepareUpload();
        cache.createOrRefreshCache("previous content");
        when(repository.findAll(Sort.by("id"))).thenReturn(
                List.of(new DocumentChunk("large.pdf", "a".repeat(200001))));
        var response = new TransactionTemplate(transactions).execute(status -> manager.upload(file));
        pendingRefreshes.get(0).run();
        assertEquals(1, response.chunks());
        verify(repository).insertChunk("test.pdf", "chunk", "[0.1]");
        assertNull(cache.getActiveCache());
    }

    @Test
    void preloadLoadsExistingDocumentsAndInvalidatesEmptyOrOversizedCache() {
        when(repository.findAll(Sort.by("id"))).thenReturn(
                List.of(new DocumentChunk("existing.pdf", "existing content")),
                List.of(), List.of(new DocumentChunk("large.pdf", "a".repeat(200001))));
        manager.preloadCagCache();
        assertEquals("existing content", cache.getActiveCache().context());
        assertDoesNotThrow(manager::preloadCagCache);
        assertNull(cache.getActiveCache());
        assertDoesNotThrow(manager::preloadCagCache);
        assertNull(cache.getActiveCache());
    }

    @Test
    void providerFailureDoesNotReportCommittedUploadAsFailed() throws Exception {
        var file = prepareUpload();
        when(repository.findAll(Sort.by("id"))).thenReturn(
                List.of(new DocumentChunk("test.pdf", "chunk")));
        doThrow(new AiProviderException("provider unavailable")).when(chat).preload(any(), any());
        assertDoesNotThrow(() -> new TransactionTemplate(transactions)
                .execute(status -> manager.upload(file)));
        assertDoesNotThrow(() -> pendingRefreshes.get(0).run());
        assertNull(cache.getActiveCache());
        assertTrue(transactions.commits.get() > 0);
    }

    @Test
    void rejectedRefreshDoesNotTurnCommittedUploadIntoFailure() throws Exception {
        var file = prepareUpload();
        cache.createOrRefreshCache("previous content");
        var rejectingManager = new DocumentManager(new DocumentBusinessRules(), chunking, embedding,
                repository, cache, new CagBusinessRules(50000), transactions,
                task -> { throw new TaskRejectedException("executor unavailable"); });
        assertDoesNotThrow(() -> new TransactionTemplate(transactions)
                .execute(status -> rejectingManager.upload(file)));
        assertNull(cache.getActiveCache());
        cache.createOrRefreshCache("recovered content");
        assertNotNull(cache.getReadyCache());
        verify(repository, never()).findAll(any(Sort.class));
    }

    @Test
    void committedUploadMakesAutoRoutingWaitForTheNewContext() throws Exception {
        var file = prepareUpload();
        cache.createOrRefreshCache("previous content");
        var previous = cache.getActiveCache();
        when(repository.findAll(Sort.by("id"))).thenReturn(
                List.of(new DocumentChunk("test.pdf", "updated content")));
        new TransactionTemplate(transactions).execute(status -> manager.upload(file));
        assertSame(previous, cache.getActiveCache());
        assertNull(cache.getReadyCache());
        pendingRefreshes.get(0).run();
        assertEquals("updated content", cache.getReadyCache().context());
    }

    @Test
    void concurrentRefreshesCannotOverwriteNewerContextWithOlderContext() throws Exception {
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(repository.findAll(Sort.by("id"))).thenAnswer(call -> {
            if (reads.incrementAndGet() == 1) {
                firstRead.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                return List.of(new DocumentChunk("a.pdf", "A"));
            }
            return List.of(new DocumentChunk("a.pdf", "A"), new DocumentChunk("b.pdf", "B"));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(manager::preloadCagCache);
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondStarted.countDown();
                manager.preloadCagCache();
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> second.get(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals("A\n\nB", cache.getActiveCache().context());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private MockMultipartFile prepareUpload() throws IOException {
        when(chunking.chunk(any())).thenReturn(List.of("chunk"));
        when(embedding.embed("chunk")).thenReturn("[0.1]");
        return new MockMultipartFile("file", "test.pdf", "application/pdf", pdfBytes());
    }

    private byte[] pdfBytes() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("document text");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
    // Gerçek Spring commit/rollback callback'lerini çalıştırır; testte DB bağlantısı açmaz.
    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final AtomicInteger commits = new AtomicInteger();
        private boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("commit failed");
            }
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
