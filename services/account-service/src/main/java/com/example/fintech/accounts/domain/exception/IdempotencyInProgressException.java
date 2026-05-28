package com.example.fintech.accounts.domain.exception;

import java.util.Map;

public final class IdempotencyInProgressException extends DomainException {
    private final long retryAfterSeconds;

    public IdempotencyInProgressException(long retryAfterSeconds) {
        super("Idempotent operation is in progress");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public String code() {
        return "IDEMPOTENCY_IN_PROGRESS";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("retryAfterSeconds", retryAfterSeconds);
    }
}
