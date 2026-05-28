package com.example.fintech.auth.domain.exception;

import java.util.Map;

public final class AccountLockedException extends DomainException {
    private final long retryAfterSeconds;
    public AccountLockedException(long retryAfterSeconds) {
        super("Account locked, retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
    @Override public String code() { return "ACCOUNT_LOCKED"; }
    @Override public Map<String, Object> params() { return Map.of("retryAfterSeconds", retryAfterSeconds); }
}
