package com.example.fintech.accounting.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Inbox-pattern dedupe row for the accounting Kafka consumer.
 *
 * <p>Insert succeeds → we own this event's processing. {@code DuplicateKeyException} → another
 * delivery already processed it; ack and skip. The collection has a TTL on {@code expireAt} so
 * old dedupe rows are evicted after the retention window (no event delivery is realistically
 * delayed that long).
 */
@Document(collection = "inbox_accounting")
public class ProcessedEventDocument {

    @Id
    private String eventId;

    @Field("topic") private String topic;
    @Field("eventType") private String eventType;
    @Field("consumerGroup") private String consumerGroup;
    @Field("processedAt") private Instant processedAt;
    @Field("expireAt") private Instant expireAt;

    public ProcessedEventDocument() {}

    public ProcessedEventDocument(String eventId, String topic, String eventType,
                                   String consumerGroup, Instant processedAt, Instant expireAt) {
        this.eventId = eventId;
        this.topic = topic;
        this.eventType = eventType;
        this.consumerGroup = consumerGroup;
        this.processedAt = processedAt;
        this.expireAt = expireAt;
    }

    public String getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public String getEventType() { return eventType; }
    public String getConsumerGroup() { return consumerGroup; }
    public Instant getProcessedAt() { return processedAt; }
    public Instant getExpireAt() { return expireAt; }
}
