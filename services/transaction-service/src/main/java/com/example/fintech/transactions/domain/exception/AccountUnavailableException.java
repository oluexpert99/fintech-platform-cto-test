package com.example.fintech.transactions.domain.exception;

import com.example.fintech.transactions.domain.model.AccountId;

import java.util.Map;

public final class AccountUnavailableException extends DomainException {

    private final AccountId accountId;
    private final String status;

    public AccountUnavailableException(AccountId accountId, String status) {
        super("Account " + accountId + " is " + status);
        this.accountId = accountId;
        this.status = status;
    }

    public AccountId accountId() {
        return accountId;
    }

    public String status() {
        return status;
    }

    @Override
    public String code() {
        return "ACCOUNT_UNAVAILABLE";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("accountId", accountId.value(), "status", status);
    }
}
