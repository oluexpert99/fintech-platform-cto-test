package com.example.fintech.auth.domain.exception;

import java.util.Map;

public final class InvalidCredentialsException extends DomainException {
    public InvalidCredentialsException() { super("Invalid email or password"); }
    @Override public String code() { return "INVALID_CREDENTIALS"; }
    @Override public Map<String, Object> params() { return Map.of(); }
}
