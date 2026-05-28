package com.example.fintech.accounts.messaging.envelope;

import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class EventEnvelopeBuilder {
    public Map<String, Object> envelope(String eventType, Object payload) {
        return Map.of(
                "eventId", UlidCreator.getMonotonicUlid().toString(),
                "eventType", eventType,
                "occurredAt", Instant.now().toString(),
                "payload", payload
        );
    }
}
