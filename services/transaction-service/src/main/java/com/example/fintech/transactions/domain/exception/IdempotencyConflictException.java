package com.example.fintech.transactions.domain.exception;

import java.util.Map;

public final class IdempotencyConflictException extends DomainException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key reused with a different payload: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public String code() {
        return "IDEMPOTENCY_KEY_CONFLICT";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("idempotencyKey", idempotencyKey);
    }
}
