package com.example.fintech.transactions.domain.exception;

import com.example.fintech.transactions.domain.model.AccountId;

import java.util.Map;

public final class InsufficientFundsException extends DomainException {

    private final AccountId accountId;
    private final long available;
    private final long requested;
    private final String currency;

    public InsufficientFundsException(AccountId accountId, long available, long requested, String currency) {
        super("Account " + accountId + " has balance " + available + " " + currency + ", requested " + requested);
        this.accountId = accountId;
        this.available = available;
        this.requested = requested;
        this.currency = currency;
    }

    public AccountId accountId() {
        return accountId;
    }

    public long available() {
        return available;
    }

    public long requested() {
        return requested;
    }

    public String currency() {
        return currency;
    }

    @Override
    public String code() {
        return "INSUFFICIENT_FUNDS";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of(
                "accountId", accountId.value(),
                "available", available,
                "requested", requested,
                "currency", currency);
    }
}
