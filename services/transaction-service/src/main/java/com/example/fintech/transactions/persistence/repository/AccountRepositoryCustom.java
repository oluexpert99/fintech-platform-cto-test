package com.example.fintech.transactions.persistence.repository;

import com.example.fintech.transactions.domain.model.AccountId;

/**
 * Custom repository methods that go through {@link org.springframework.data.mongodb.core.MongoTemplate}
 * to issue conditional updates. See {@code transaction-service.spec} §3.3 for the contract.
 *
 * <p>These methods must run inside an active Mongo multi-document transaction —
 * the {@code TransferService} wraps them in a {@code @Transactional} boundary.
 */
public interface AccountRepositoryCustom {

    /**
     * Atomic debit guarded by a balance precondition.
     *
     * <p>Returns {@code true} if matched-and-modified, {@code false} if the precondition didn't hold
     * (balance &lt; amount, or status != ACTIVE, or version mismatch). On {@code false}, the
     * service layer re-reads the account to disambiguate the cause.
     */
    boolean conditionalDebit(AccountId source, long amount, long expectedVersion);

    /**
     * Unconditional credit. Still verifies the destination is ACTIVE and version matches;
     * returns {@code false} if either fails.
     */
    boolean conditionalCredit(AccountId destination, long amount, long expectedVersion);
}
