package com.example.rag.business.rules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.rag.core.exceptions.types.BusinessException;

@Component
public class CagBusinessRules {

    private final int maxContextTokens;

    public CagBusinessRules(@Value("${ai.cag.max-context-tokens:50000}") int maxContextTokens) {
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException("CAG token limit must be positive.");
        }
        this.maxContextTokens = maxContextTokens;
    }

    public void questionMustBeValid(String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("Question must not be blank.");
        }
    }

    public void contextMustBeValid(String context) {
        if (context == null || context.isBlank()) {
            throw new BusinessException(
                    "No document content available. Please upload a document first.");
        }

        long estimatedTokens = (context.length() + 3L) / 4L;
        if (estimatedTokens > maxContextTokens) {
            throw new BusinessException("Knowledge context exceeds the CAG token limit.");
        }
    }

    public void activeCacheMustExist(String cacheId) {
        if (cacheId == null || cacheId.isBlank()) {
            throw new BusinessException(
                    "CAG cache is unavailable or preparing. "
                    + "Check document content, CAG limits and AI configuration.");
        }
    }

}
