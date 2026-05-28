package com.example.fintech.transactions.persistence.repository;

import com.example.fintech.transactions.persistence.document.OutboxRecordDocument;

import java.time.Duration;
import java.util.List;

/**
 * Custom fragment for the lease-based claim algorithm in
 * {@code transaction-service.spec} §4.4.1.
 *
 * <p>Without lease-claim, a naive {@code @Scheduled} polling worker running in every replica
 * would publish the same row in parallel. Consumer dedupe would catch it, but Kafka load and
 * metrics would inflate. The lease pattern prevents that without leader election.
 */
public interface OutboxRepositoryCustom {

    /**
     * Atomically claim up to {@code limit} PENDING rows whose lease has expired, setting their
     * lease to {@code now + leaseDuration} owned by {@code instanceId}. Returns the claimed rows.
     *
     * <p>Implemented via {@link org.springframework.data.mongodb.core.MongoTemplate#findAndModify}
     * with a sort on {@code createdAt}. Two replicas competing for the same row see at-most-one
     * claim succeed — the other gets no rows on this tick.
     */
    List<OutboxRecordDocument> claimPending(int limit, Duration leaseDuration, String instanceId);

    /** Release a previously-claimed lease so another replica can take the row. */
    void releaseLease(String id);

    /** Mark a row sent; sets sentAt and clears the lease. */
    void markSent(String id);

    /** Increment attempts; record the last error message. */
    void incrementAttempts(String id, String lastError);

    /** Mark a row as poisoned (terminal state). */
    void markPoisoned(String id, String lastError);
}
