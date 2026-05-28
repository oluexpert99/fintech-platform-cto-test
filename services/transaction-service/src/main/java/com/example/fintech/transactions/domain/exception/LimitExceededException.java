package com.example.fintech.transactions.domain.exception;

import java.util.Map;

public final class LimitExceededException extends DomainException {

    private final String limitType;
    private final long requested;
    private final long maxAllowed;
    private final String currency;

    public LimitExceededException(String limitType, long requested, long maxAllowed, String currency) {
        super(limitType + " limit exceeded: requested " + requested + ", max " + maxAllowed + " " + currency);
        this.limitType = limitType;
        this.requested = requested;
        this.maxAllowed = maxAllowed;
        this.currency = currency;
    }

    public String limitType() {
        return limitType;
    }

    public long requested() {
        return requested;
    }

    public long maxAllowed() {
        return maxAllowed;
    }

    public String currency() {
        return currency;
    }

    @Override
    public String code() {
        return "LIMIT_EXCEEDED";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of(
                "limitType", limitType,
                "requested", requested,
                "maxAllowed", maxAllowed,
                "currency", currency);
    }
}
