package com.example.fintech.accounts.persistence.repository;

import com.example.fintech.accounts.persistence.document.OutboxRecordDocument;

import java.time.Duration;
import java.util.List;

public interface OutboxRepositoryCustom {
    List<OutboxRecordDocument> claimPending(int limit, Duration leaseDuration, String instanceId);
    void markSent(String id);
    void incrementAttempts(String id, String lastError);
    void markPoisoned(String id, String lastError);
}
