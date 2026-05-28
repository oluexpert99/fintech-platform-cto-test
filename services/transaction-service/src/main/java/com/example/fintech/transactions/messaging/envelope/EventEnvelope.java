package com.example.fintech.transactions.messaging.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Shared event envelope per {@code events.spec} §4. Producer-side representation;
 * serialised to JSON for the outbox payload and the Kafka value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Instant producedAt,
        String producer,
        String correlationId,
        String causationId,
        String traceparent,
        T data
) { }
