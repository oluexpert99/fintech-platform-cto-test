package com.example.fintech.auth.domain.exception;

import java.util.Map;

public final class MfaInvalidException extends DomainException {
    /** When true the handler returns 422 (verifications context); when false it returns 401 (login context). */
    private final boolean userAlreadyAuthenticated;
    public MfaInvalidException(boolean userAlreadyAuthenticated) {
        super("MFA OTP invalid");
        this.userAlreadyAuthenticated = userAlreadyAuthenticated;
    }
    public boolean userAlreadyAuthenticated() { return userAlreadyAuthenticated; }
    @Override public String code() { return "MFA_INVALID"; }
    @Override public Map<String, Object> params() { return Map.of(); }
}
