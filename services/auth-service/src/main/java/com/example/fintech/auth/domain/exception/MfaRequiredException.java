package com.example.fintech.auth.domain.exception;

import java.util.List;
import java.util.Map;

public final class MfaRequiredException extends DomainException {
    private final List<String> availableFactors;
    public MfaRequiredException(List<String> availableFactors) {
        super("MFA required");
        this.availableFactors = availableFactors == null ? List.of("TOTP") : availableFactors;
    }
    public List<String> availableFactors() { return availableFactors; }
    @Override public String code() { return "MFA_REQUIRED"; }
    @Override public Map<String, Object> params() { return Map.of("availableFactors", availableFactors); }
}
