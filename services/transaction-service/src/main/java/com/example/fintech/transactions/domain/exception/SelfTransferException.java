package com.example.fintech.transactions.domain.exception;

import com.example.fintech.transactions.domain.model.AccountId;

import java.util.Map;

public final class SelfTransferException extends DomainException {

    private final AccountId accountId;

    public SelfTransferException(AccountId accountId) {
        super("Cannot transfer from an account to itself: " + accountId);
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }

    @Override
    public String code() {
        return "SELF_TRANSFER";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("accountId", accountId.value());
    }
}
