package com.example.fintech.accounts.domain.exception;

import com.example.fintech.accounts.domain.model.AccountStatus;

import java.util.Map;

public final class InvalidStateTransitionException extends DomainException {
    private final AccountStatus from;
    private final AccountStatus to;

    public InvalidStateTransitionException(AccountStatus from, AccountStatus to, String reason) {
        super("Invalid transition from " + from + " to " + to + ": " + reason);
        this.from = from;
        this.to = to;
    }

    @Override
    public String code() {
        return "INVALID_STATE_TRANSITION";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("from", from.name(), "to", to.name());
    }
}
