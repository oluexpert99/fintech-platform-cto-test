package com.example.fintech.accounts.persistence.repository;

import com.example.fintech.accounts.persistence.document.OutboxRecordDocument;
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

public class OutboxRepositoryImpl implements OutboxRepositoryCustom {
    private final MongoTemplate mongoTemplate;

    public OutboxRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<OutboxRecordDocument> claimPending(int limit, Duration leaseDuration, String instanceId) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(leaseDuration);
        FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);
        List<OutboxRecordDocument> rows = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Query query = new Query(Criteria.where("status").is("PENDING").and("leaseUntil").lt(now))
                    .with(Sort.by(Sort.Direction.ASC, "createdAt"));
            Update update = new Update().set("leaseUntil", leaseUntil).set("leasedBy", instanceId);
            OutboxRecordDocument row = mongoTemplate.findAndModify(query, update, options, OutboxRecordDocument.class);
            if (row == null) {
                break;
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public void markSent(String id) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("status", "SENT").set("sentAt", Instant.now()).set("leaseUntil", Instant.EPOCH).unset("leasedBy"),
                OutboxRecordDocument.class);
    }

    @Override
    public void incrementAttempts(String id, String lastError) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().inc("attempts", 1L).set("lastError", lastError).set("leaseUntil", Instant.EPOCH).unset("leasedBy"),
                OutboxRecordDocument.class);
    }

    @Override
    public void markPoisoned(String id, String lastError) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("status", "POISONED").set("lastError", lastError).set("leaseUntil", Instant.EPOCH).unset("leasedBy"),
                OutboxRecordDocument.class);
    }
}
