package com.example.fintech.transactions.persistence.repository;

import com.example.fintech.transactions.persistence.document.OutboxRecordDocument;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoTemplate-backed implementation of {@link OutboxRepositoryCustom}. Class name MUST end in
 * {@code Impl} so Spring Data picks it up as the fragment for {@link OutboxRepository}.
 */
public class OutboxRepositoryImpl implements OutboxRepositoryCustom {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_POISONED = "POISONED";

    private final MongoTemplate mongoTemplate;

    public OutboxRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<OutboxRecordDocument> claimPending(int limit, Duration leaseDuration, String instanceId) {
        Instant now = Instant.now();
        Instant newLeaseUntil = now.plus(leaseDuration);
        List<OutboxRecordDocument> claimed = new ArrayList<>(limit);

        // Mongo doesn't have a single-call findAndModify-many. Claim one row at a time with a
        // sorted query; this serialises lease updates so two replicas can never claim the same row.
        FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);

        for (int i = 0; i < limit; i++) {
            Query query = new Query(Criteria.where("status").is(STATUS_PENDING)
                            .and("leaseUntil").lt(now))
                    .with(Sort.by(Sort.Direction.ASC, "createdAt"));
            Update update = new Update()
                    .set("leaseUntil", newLeaseUntil)
                    .set("leasedBy", instanceId);
            OutboxRecordDocument claimedRow = mongoTemplate.findAndModify(query, update, options, OutboxRecordDocument.class);
            if (claimedRow == null) {
                break;
            }
            claimed.add(claimedRow);
        }
        return claimed;
    }

    @Override
    public void releaseLease(String id) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update().set("leaseUntil", Instant.EPOCH).unset("leasedBy");
        mongoTemplate.updateFirst(query, update, OutboxRecordDocument.class);
    }

    @Override
    public void markSent(String id) {
        Instant now = Instant.now();
        Query query = new Query(Criteria.where("_id").is(id));
        // Set expireAt ONLY on successful send. PENDING and POISONED rows have null expireAt
        // and survive Mongo's TTL sweep — they need human attention, not auto-deletion.
        Update update = new Update()
                .set("status", STATUS_SENT)
                .set("sentAt", now)
                .set("expireAt", now.plus(Duration.ofDays(7)))
                .set("leaseUntil", Instant.EPOCH)
                .unset("leasedBy");
        mongoTemplate.updateFirst(query, update, OutboxRecordDocument.class);
    }

    @Override
    public void incrementAttempts(String id, String lastError) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update()
                .inc("attempts", 1L)
                .set("lastError", lastError)
                .set("leaseUntil", Instant.EPOCH)
                .unset("leasedBy");
        mongoTemplate.updateFirst(query, update, OutboxRecordDocument.class);
    }

    @Override
    public void markPoisoned(String id, String lastError) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update()
                .set("status", STATUS_POISONED)
                .set("lastError", lastError)
                .set("leaseUntil", Instant.EPOCH)
                .unset("leasedBy");
        mongoTemplate.updateFirst(query, update, OutboxRecordDocument.class);
    }
}
