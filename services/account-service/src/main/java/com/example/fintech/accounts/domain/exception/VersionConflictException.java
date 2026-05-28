package com.example.fintech.accounts.domain.exception;

import java.util.Map;

public final class VersionConflictException extends DomainException {
    private final long expected;
    private final long actual;

    public VersionConflictException(long expected, long actual) {
        super("Version conflict. expected=" + expected + " actual=" + actual);
        this.expected = expected;
        this.actual = actual;
    }

    @Override
    public String code() {
        return "VERSION_CONFLICT";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("expected", expected, "actual", actual);
    }
}
