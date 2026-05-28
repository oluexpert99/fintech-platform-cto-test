package com.example.fintech.transactions.api.dto;

import com.example.fintech.transactions.domain.model.TransactionStatus;
import com.example.fintech.transactions.domain.model.TransactionType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Outbound representation of a {@code transactions} document.
 * See {@code api.md} §10.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(
        String transactionId,
        TransactionType type,
        TransactionStatus status,
        String sourceAccount,
        String destinationAccount,
        Long amount,
        String currency,
        String description,
        List<String> journalLineIds,
        String correctsTransactionId,
        String reason,
        String approverId,
        Instant createdAt,
        Instant completedAt
) { }
