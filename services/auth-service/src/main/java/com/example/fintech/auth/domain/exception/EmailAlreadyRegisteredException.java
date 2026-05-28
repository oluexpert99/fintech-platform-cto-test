package com.example.fintech.auth.domain.exception;

import java.util.Map;

public final class EmailAlreadyRegisteredException extends DomainException {
    private final String email;
    public EmailAlreadyRegisteredException(String email) { super("Email already registered: " + email); this.email = email; }
    public String email() { return email; }
    @Override public String code() { return "EMAIL_ALREADY_REGISTERED"; }
    @Override public Map<String, Object> params() { return Map.of("email", email); }
}
