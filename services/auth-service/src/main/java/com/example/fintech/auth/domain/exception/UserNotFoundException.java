package com.example.fintech.auth.domain.exception;

import com.example.fintech.auth.domain.model.UserId;

import java.util.Map;

public final class UserNotFoundException extends DomainException {
    private final UserId userId;
    public UserNotFoundException(UserId userId) { super("User not found: " + userId); this.userId = userId; }
    public UserId userId() { return userId; }
    @Override public String code() { return "RESOURCE_NOT_FOUND"; }
    @Override public Map<String, Object> params() { return Map.of("userId", userId.value()); }
}
