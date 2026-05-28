package com.example.fintech.transactions.domain.exception;

import java.util.Map;

public final class MissingIdempotencyKeyException extends DomainException {

    public MissingIdempotencyKeyException() {
        super("Idempotency-Key header is required on this endpoint");
    }

    @Override
    public String code() {
        return "MISSING_IDEMPOTENCY_KEY";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of();
    }
}
