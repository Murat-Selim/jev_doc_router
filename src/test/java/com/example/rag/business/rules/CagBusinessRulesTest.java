package com.example.rag.business.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.example.rag.core.exceptions.types.BusinessException;

class CagBusinessRulesTest {

    @Test
    void estimatedTokenLimitAcceptsBoundaryAndRejectsOverflow() {
        var rules = new CagBusinessRules(3);
        assertDoesNotThrow(() -> rules.contextMustBeValid("a".repeat(12)));
        assertThrows(BusinessException.class, () -> rules.contextMustBeValid("a".repeat(13)));
    }

    @Test
    void rejectsMissingContextCacheAndInvalidConfiguration() {
        var rules = new CagBusinessRules(3);
        assertThrows(BusinessException.class, () -> rules.contextMustBeValid(null));
        assertThrows(BusinessException.class, () -> rules.contextMustBeValid(" "));
        assertThrows(BusinessException.class, () -> rules.activeCacheMustExist(null));
        assertThrows(BusinessException.class, () -> rules.activeCacheMustExist(" "));
        assertThrows(IllegalArgumentException.class, () -> new CagBusinessRules(0));
        assertThrows(IllegalArgumentException.class, () -> new CagBusinessRules(-1));
    }

}
