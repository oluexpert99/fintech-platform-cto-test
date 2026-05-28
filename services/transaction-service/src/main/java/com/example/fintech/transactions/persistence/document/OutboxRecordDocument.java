package com.example.fintech.transactions.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Map;

/**
 * The transactional-outbox row inserted in the same Mongo transaction as the business write.
 * Drained by {@link com.example.fintech.transactions.messaging.OutboxPublisher} (see {@code transaction-service.spec} §4.4).
 *
 * <p>Collection name is per-service: {@code outbox_txn} for this service.
 */
@Document(collection = "outbox_txn")
public class OutboxRecordDocument {

    @Id
    private String id;

    /** Aggregate this event is about — used as the Kafka partition key. */
    @Field("aggregateId")
    private String aggregateId;

    @Field("topic")
    private String topic;

    /** ULID; the consumer's inbox-pattern dedupe key. */
    @Field("eventId")
    private String eventId;

    /** The full event envelope, serialised. */
    @Field("payload")
    private Map<String, Object> payload;

    /** PENDING | SENT | POISONED. */
    @Field("status")
    private String status;

    @Field("attempts")
    private int attempts;

    /** Lease used by the publisher to claim the row; never-leased rows have this in the epoch. */
    @Field("leaseUntil")
    private Instant leaseUntil;

    @Field("leasedBy")
    private String leasedBy;

    @Field("createdAt")
    private Instant createdAt;

    @Field("sentAt")
    private Instant sentAt;

    @Field("expireAt")
    private Instant expireAt;

    @Field("lastError")
    private String lastError;

    public OutboxRecordDocument() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }

    public String getLeasedBy() { return leasedBy; }
    public void setLeasedBy(String leasedBy) { this.leasedBy = leasedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
