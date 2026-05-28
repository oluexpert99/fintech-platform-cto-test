package com.example.fintech.transactions.messaging.envelope;

import com.github.f4b6a3.ulid.UlidCreator;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Builds the shared {@link EventEnvelope} around a typed payload. Single source of truth for
 * envelope construction; no service builds envelopes by hand.
 *
 * <p>Envelope conformance is asserted by tests against the JSON Schema in
 * {@code events/schemas/_envelope.v1.json}.
 */
@Component
public class EventEnvelopeBuilder {

    private final String producerLabel;

    public EventEnvelopeBuilder(
            @Value("${spring.application.name:transaction-service}") String appName,
            @Value("${app.version:0.1.0-SNAPSHOT}") String appVersion) {
        this.producerLabel = appName + "@" + appVersion;
    }

    public <T> EventEnvelope<T> wrap(String eventType, int eventVersion, T data, Instant occurredAt) {
        Instant now = Instant.now();
        return new EventEnvelope<>(
                UlidCreator.getUlid().toString(),
                eventType,
                eventVersion,
                occurredAt != null ? occurredAt : now,
                now,
                producerLabel,
                MDC.get("correlationId"),
                null,                    // causationId — populated from inbox when this event is in response to another
                MDC.get("traceparent"),  // W3C trace context, populated by OTel instrumentation
                data);
    }
}
