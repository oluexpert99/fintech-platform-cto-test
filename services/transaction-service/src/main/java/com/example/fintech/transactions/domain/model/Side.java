package com.example.fintech.transactions.domain.model;

/**
 * Double-entry bookkeeping side. A transfer produces exactly two journal lines:
 * a DEBIT on the source and a CREDIT on the destination.
 */
public enum Side {
    DEBIT,
    CREDIT
}
