package com.example.fintech.transactions.domain.exception;

import com.example.fintech.transactions.domain.model.AccountId;

import java.util.Map;

public final class AccountNotFoundException extends DomainException {

    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }

    @Override
    public String code() {
        return "RESOURCE_NOT_FOUND";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("accountId", accountId.value());
    }
}
