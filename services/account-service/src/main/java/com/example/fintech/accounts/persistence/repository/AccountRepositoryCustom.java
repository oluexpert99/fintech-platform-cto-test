package com.example.fintech.accounts.persistence.repository;

import com.example.fintech.accounts.persistence.document.AccountDocument;

import java.util.Optional;

public interface AccountRepositoryCustom {
    Optional<AccountDocument> findByIdWithMajority(String id);
    Optional<AccountDocument> updateIfVersionMatches(AccountDocument account, long expectedVersion);
}
