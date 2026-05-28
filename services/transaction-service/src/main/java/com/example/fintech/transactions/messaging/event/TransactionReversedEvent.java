package com.example.fintech.transactions.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Payload of {@code transactions.transfer.reversed} (v1) — must match
 * {@code events/schemas/transactions.transfer.reversed-value.v1.json}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionReversedEvent(
        String transactionId,
        String type,                       // always "REVERSAL" on this topic
        String correctsTransactionId,
        String sourceAccount,              // mirrors the REVERSAL doc; semantically the side that received the compensating credit
        String destinationAccount,
        long amount,
        String currency,
        Instant completedAt,
        String reason
) {
    public static final String EVENT_TYPE = "TransactionReversedEvent";
    public static final int EVENT_VERSION = 1;
    public static final String TOPIC = "transactions.transfer.reversed";
}
