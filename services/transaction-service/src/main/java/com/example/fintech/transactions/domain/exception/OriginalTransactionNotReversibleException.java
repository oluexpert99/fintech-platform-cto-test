package com.example.fintech.transactions.domain.exception;

import com.example.fintech.transactions.domain.model.TransactionId;

import java.util.Map;

public final class OriginalTransactionNotReversibleException extends DomainException {

    private final TransactionId originalId;
    private final String currentStatus;

    public OriginalTransactionNotReversibleException(TransactionId originalId, String currentStatus) {
        super("Transaction " + originalId + " cannot be reversed (status=" + currentStatus + ")");
        this.originalId = originalId;
        this.currentStatus = currentStatus;
    }

    public TransactionId originalId() {
        return originalId;
    }

    public String currentStatus() {
        return currentStatus;
    }

    @Override
    public String code() {
        return "ORIGINAL_TRANSACTION_NOT_REVERSIBLE";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of(
                "originalTransactionId", originalId.value(),
                "currentStatus", currentStatus);
    }
}
