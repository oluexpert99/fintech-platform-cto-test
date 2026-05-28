package com.example.fintech.auth.domain.exception;

import java.util.Map;

public final class RefreshTokenRevokedException extends DomainException {
    public RefreshTokenRevokedException() { super("Refresh token has been revoked or rotated"); }
    @Override public String code() { return "REFRESH_TOKEN_REVOKED"; }
    @Override public Map<String, Object> params() { return Map.of(); }
}
