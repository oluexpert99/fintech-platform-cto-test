package com.example.fintech.transactions.persistence.init;

import com.example.fintech.transactions.persistence.document.JournalEntryDocument;
import com.example.fintech.transactions.persistence.document.OutboxRecordDocument;
import com.example.fintech.transactions.persistence.document.TransactionDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Creates required Mongo indexes on application startup.
 *
 * <p><strong>Runs on {@link ApplicationStartedEvent}</strong>, not {@code ApplicationReadyEvent} —
 * this fires after context refresh but <em>before</em> readiness flips to ACCEPTING_TRAFFIC.
 * Indexes are therefore guaranteed to exist by the time the first request can land. (The old
 * version ran on ReadyEvent, leaving a small window where the unique idempotency index didn't
 * exist yet.)
 *
 * <p>Replaces the Mongock-based migrations that existed under Spring Boot 3.x. Mongock does not
 * yet ship a Spring-Boot-4-compatible artifact. {@code createIndex} is idempotent so the bean
 * survives restarts cleanly.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final MongoTemplate mongoTemplate;

    public SchemaInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void initialise() {
        log.info("Initialising MongoDB indexes for transaction-service");
        initTransactionsIndexes();
        initJournalIndexes();
        initOutboxIndexes();
        log.info("MongoDB indexes initialised — readiness can flip to UP");
    }

    private void initTransactionsIndexes() {
        IndexOperations idx = mongoTemplate.indexOps(TransactionDocument.class);
        // idempotencyKey is the structural defence against double-debit; unique index is the arbiter.
        idx.createIndex(new Index().on("idempotencyKey", Sort.Direction.ASC).unique());
        idx.createIndex(new Index().on("sourceAccount", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        idx.createIndex(new Index().on("destinationAccount", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        idx.createIndex(new Index().on("callerSub", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        // Reversal lookup — supports `existsByCorrectsTransactionId` and audit queries.
        idx.createIndex(new Index().on("correctsTransactionId", Sort.Direction.ASC).sparse());
    }

    private void initJournalIndexes() {
        IndexOperations idx = mongoTemplate.indexOps(JournalEntryDocument.class);
        idx.createIndex(new Index().on("transactionId", Sort.Direction.ASC));
        idx.createIndex(new Index().on("account", Sort.Direction.ASC).on("postedAt", Sort.Direction.DESC));
        idx.createIndex(new Index().on("postedAt", Sort.Direction.ASC));
        // Supports the accounting projector + trial-balance aggregations.
        idx.createIndex(new Index().on("coaAccount", Sort.Direction.ASC).on("postedAt", Sort.Direction.DESC));
    }

    private void initOutboxIndexes() {
        IndexOperations idx = mongoTemplate.indexOps(OutboxRecordDocument.class);
        // Publisher's lease-claim query: status + leaseUntil + createdAt
        idx.createIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("leaseUntil", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC));
        idx.createIndex(new Index().on("eventId", Sort.Direction.ASC).unique());
        // TTL on expireAt. We never set expireAt until status=SENT (see OutboxRepositoryImpl.markSent),
        // so PENDING / POISONED rows survive — they need human attention, not auto-deletion.
        // Mongo's TTL sweep skips documents whose expireAt is null/missing, so this is effectively
        // a partial TTL without needing partialFilterExpression.
        idx.createIndex(new Index().on("expireAt", Sort.Direction.ASC).expire(0L));
    }
}
