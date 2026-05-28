package com.example.fintech.transactions.domain.exception;

import com.example.fintech.transactions.domain.model.TransactionId;

import java.util.Map;

public final class TransactionNotFoundException extends DomainException {

    private final TransactionId transactionId;

    public TransactionNotFoundException(TransactionId transactionId) {
        super("Transaction not found: " + transactionId);
        this.transactionId = transactionId;
    }

    public TransactionId transactionId() {
        return transactionId;
    }

    @Override
    public String code() {
        return "RESOURCE_NOT_FOUND";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("transactionId", transactionId.value());
    }
}
