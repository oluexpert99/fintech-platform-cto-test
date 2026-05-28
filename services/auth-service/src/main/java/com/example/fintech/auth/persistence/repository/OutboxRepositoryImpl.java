package com.example.fintech.auth.persistence.repository;

import com.example.fintech.auth.persistence.document.OutboxRecordDocument;
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
 * Lease-claim outbox publisher fragment for auth-service. Same algorithm as transaction-service —
 * see {@code transaction-service.spec} §4.4.1.
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
        FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);

        for (int i = 0; i < limit; i++) {
            Query query = new Query(Criteria.where("status").is(STATUS_PENDING)
                            .and("leaseUntil").lt(now))
                    .with(Sort.by(Sort.Direction.ASC, "createdAt"));
            Update update = new Update()
                    .set("leaseUntil", newLeaseUntil)
                    .set("leasedBy", instanceId);
            OutboxRecordDocument row = mongoTemplate.findAndModify(query, update, options, OutboxRecordDocument.class);
            if (row == null) break;
            claimed.add(row);
        }
        return claimed;
    }

    @Override
    public void releaseLease(String id) {
        mongoTemplate.updateFirst(byId(id),
                new Update().set("leaseUntil", Instant.EPOCH).unset("leasedBy"),
                OutboxRecordDocument.class);
    }

    @Override
    public void markSent(String id) {
        mongoTemplate.updateFirst(byId(id),
                new Update().set("status", STATUS_SENT)
                        .set("sentAt", Instant.now())
                        .set("leaseUntil", Instant.EPOCH).unset("leasedBy"),
                OutboxRecordDocument.class);
    }

    @Override
    public void incrementAttempts(String id, String lastError) {
        mongoTemplate.updateFirst(byId(id),
                new Update().inc("attempts", 1L)
                        .set("lastError", lastError)
                        .set("leaseUntil", Instant.EPOCH).unset("leasedBy"),
                OutboxRecordDocument.class);
    }

    @Override
    public void markPoisoned(String id, String lastError) {
        mongoTemplate.updateFirst(byId(id),
                new Update().set("status", STATUS_POISONED)
                        .set("lastError", lastError)
                        .set("leaseUntil", Instant.EPOCH).unset("leasedBy"),
                OutboxRecordDocument.class);
    }

    private static Query byId(String id) {
        return new Query(Criteria.where("_id").is(id));
    }
}
