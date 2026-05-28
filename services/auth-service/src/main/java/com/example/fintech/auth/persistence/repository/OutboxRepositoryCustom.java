package com.example.fintech.auth.persistence.repository;

import com.example.fintech.auth.persistence.document.OutboxRecordDocument;

import java.time.Duration;
import java.util.List;

public interface OutboxRepositoryCustom {

    /** Atomically claim up to {@code limit} PENDING rows whose lease has expired. */
    List<OutboxRecordDocument> claimPending(int limit, Duration leaseDuration, String instanceId);

    void releaseLease(String id);
    void markSent(String id);
    void incrementAttempts(String id, String lastError);
    void markPoisoned(String id, String lastError);
}
