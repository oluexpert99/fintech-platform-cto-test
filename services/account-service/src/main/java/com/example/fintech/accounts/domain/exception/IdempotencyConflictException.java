package com.example.fintech.accounts.domain.exception;

import java.util.Map;

public final class IdempotencyConflictException extends DomainException {
    public IdempotencyConflictException() {
        super("Idempotency key reused with a different payload");
    }

    @Override
    public String code() {
        return "IDEMPOTENCY_KEY_CONFLICT";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of();
    }
}
