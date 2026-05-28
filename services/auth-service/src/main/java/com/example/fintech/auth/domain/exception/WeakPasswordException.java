package com.example.fintech.auth.domain.exception;

import java.util.Map;

public final class WeakPasswordException extends DomainException {
    private final String reason;
    public WeakPasswordException(String reason) { super("Weak password: " + reason); this.reason = reason; }
    public String reason() { return reason; }
    @Override public String code() { return "WEAK_PASSWORD"; }
    @Override public Map<String, Object> params() { return Map.of("reason", reason); }
}
