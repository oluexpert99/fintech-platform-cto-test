package com.example.fintech.transactions.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Payload of {@code transactions.transfer.completed} (v1). Must match
 * {@code events/schemas/transactions.transfer.completed-value.v1.json}.
 *
 * <p>No PII (no userId, name, email): consumers look up users by {@code sourceAccount} if needed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionCompletedEvent(
        String transactionId,
        String type,                  // always "TRANSFER" on this topic
        String sourceAccount,
        String destinationAccount,
        long amount,
        String currency,
        String description,
        Instant completedAt
) {
    public static final String EVENT_TYPE = "TransactionCompletedEvent";
    public static final int EVENT_VERSION = 1;
    public static final String TOPIC = "transactions.transfer.completed";
}
