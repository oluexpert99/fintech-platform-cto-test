package com.example.fintech.accounts.persistence.init;

import com.example.fintech.accounts.persistence.document.AccountDocument;
import com.example.fintech.accounts.persistence.document.OutboxRecordDocument;
import com.example.fintech.accounts.persistence.document.PendingApprovalDocument;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class SchemaInitializer {
    private final MongoTemplate mongoTemplate;

    public SchemaInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialise() {
        var accountIdx = mongoTemplate.indexOps(AccountDocument.class);
        accountIdx.createIndex(new Index().on("idempotencyKey", Sort.Direction.ASC).unique());
        accountIdx.createIndex(new Index().on("ownerUserId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));

        var outboxIdx = mongoTemplate.indexOps(OutboxRecordDocument.class);
        outboxIdx.createIndex(new Index().on("status", Sort.Direction.ASC).on("leaseUntil", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC));
        outboxIdx.createIndex(new Index().on("eventId", Sort.Direction.ASC).unique());
        outboxIdx.createIndex(new Index().on("expireAt", Sort.Direction.ASC).expire(0L));

        var approvalsIdx = mongoTemplate.indexOps(PendingApprovalDocument.class);
        approvalsIdx.createIndex(new Index()
                .on("accountId", Sort.Direction.ASC)
                .on("approverId", Sort.Direction.ASC)
                .on("reason", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC));
        approvalsIdx.createIndex(new Index().on("expiresAt", Sort.Direction.ASC));
    }
}
