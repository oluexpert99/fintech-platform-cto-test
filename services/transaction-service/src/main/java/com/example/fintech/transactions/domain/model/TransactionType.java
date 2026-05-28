package com.example.fintech.transactions.domain.model;

/**
 * Discriminator for {@code transactions} documents — see {@code api.md} §10
 * and {@code transaction-service.spec} §10.
 */
public enum TransactionType {
    TRANSFER,
    REVERSAL,
    FEE,
    REFUND
}
