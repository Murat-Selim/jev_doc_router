package com.example.rag.business.concretes;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.data.domain.Sort;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.rag.business.abstracts.ChunkingService;
import com.example.rag.business.abstracts.DocumentService;
import com.example.rag.business.abstracts.EmbeddingService;
import com.example.rag.business.responses.documentResponse.DocumentUploadResponse;
import com.example.rag.business.rules.CagBusinessRules;
import com.example.rag.business.rules.DocumentBusinessRules;
import com.example.rag.core.ai.cache.CagCacheProvider;
import com.example.rag.dataAccess.abstracts.DocumentChunkRepository;
import com.example.rag.entities.concretes.DocumentChunk;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentManager implements DocumentService {

    private final DocumentBusinessRules rules;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository repository;
    private final CagCacheProvider cagCacheProvider;
    private final CagBusinessRules cagRules;
    private final PlatformTransactionManager transactionManager;
    private final TaskExecutor cacheRefreshExecutor;

    @PostConstruct
    void preloadCagCache() {
        refreshCagCache();
    }

    @Override
    @Transactional
    public DocumentUploadResponse upload(MultipartFile file) {
        rules.fileMustBeValid(file);

        String text = extractText(file);
        rules.textMustNotBeEmpty(text, file.getOriginalFilename());

        List<String> chunks = chunkingService.chunk(text);

        for (String chunk : chunks) {
            repository.insertChunk(file.getOriginalFilename(), chunk, embeddingService.embed(chunk));
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cagCacheProvider.refreshScheduled();
                try {
                    // Callback dönünce upload bağlantısı serbest kalır; prewarm onu bekletmez.
                    cacheRefreshExecutor.execute(() -> {
                        try {
                            refreshCagCache();
                        } finally {
                            cagCacheProvider.refreshCompleted();
                        }
                    });
                } catch (TaskRejectedException ex) {
                    cagCacheProvider.invalidateCache();
                    cagCacheProvider.refreshCompleted();
                    log.warn("CAG refresh could not be scheduled; documents were stored successfully.");
                }
            }
        });

        return new DocumentUploadResponse(
                "Document processed successfully",
                file.getOriginalFilename(),
                chunks.size()
        );
    }

    private String extractText(MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read PDF: " + file.getOriginalFilename(), e);
        }
    }

    private synchronized void refreshCagCache() {
        try {
            // Snapshot'ı kısa ve bağımsız bir read transaction içinde oluştur.
            TransactionTemplate read = new TransactionTemplate(transactionManager);
            read.setReadOnly(true);
            read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            String knowledgeContext = read.execute(status -> buildKnowledgeContext());
            cagRules.contextMustBeValid(knowledgeContext);
            cagCacheProvider.createOrRefreshCache(knowledgeContext);
        } catch (RuntimeException ex) {
            // Belgeler commit edildi; CAG sorunu RAG yüklemesini başarısız göstermemeli.
            cagCacheProvider.invalidateCache();
            log.warn("CAG cache unavailable ({}); document storage and RAG remain available.",
                    ex.getClass().getSimpleName());
        }
    }

    // ponytail: tüm chunk'ları upload sonrası tarıyoruz; büyük bilgi tabanı için job/provider cache gerekir.
    private String buildKnowledgeContext() {
        return repository.findAll(Sort.by("id")).stream()
                .map(DocumentChunk::getContent)
                .filter(content -> content != null && !content.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

}
