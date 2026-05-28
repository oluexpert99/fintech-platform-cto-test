package com.example.fintech.accounting.messaging;

/**
 * Thrown by the projector when an incoming event fails shape validation. The Spring Kafka
 * {@code DefaultErrorHandler} catches these and routes the offending record to the topic's
 * dead-letter sibling ({@code <topic>.DLT}) — never to be retried automatically. Operations is
 * expected to inspect DLT, fix the source, and manually replay.
 *
 * <p>Subclasses a runtime exception so it propagates out of the {@code @KafkaListener} method
 * cleanly without forcing a try/catch ladder.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }
}
