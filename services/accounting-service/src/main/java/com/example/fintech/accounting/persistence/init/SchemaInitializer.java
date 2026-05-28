package com.example.fintech.accounting.persistence.init;

import com.example.fintech.accounting.persistence.document.ChartOfAccountsDocument;
import com.example.fintech.accounting.persistence.document.JournalEntryDocument;
import com.example.fintech.accounting.persistence.document.ProcessedEventDocument;
import com.example.fintech.accounting.persistence.repository.ChartOfAccountsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Boots accounting-service's collections in its own database ({@code fintech_accounting}).
 *
 * <p>Runs on {@link ApplicationStartedEvent} (before {@code ApplicationReadyEvent}) so indexes
 * exist before the readiness probe goes UP — closing the small window where requests could land
 * against the unindexed projection.
 *
 * <p>Owned collections:
 * <ul>
 *   <li>{@code chart_of_accounts} — seeded with 8 system accounts on first boot</li>
 *   <li>{@code journal_projection} — materialised from Kafka events by
 *       {@link com.example.fintech.accounting.messaging.TransactionEventsProjector}</li>
 *   <li>{@code inbox_accounting} — consumer-side dedupe (events.spec §6.3)</li>
 * </ul>
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final ChartOfAccountsRepository coaRepo;
    private final MongoTemplate mongoTemplate;

    public SchemaInitializer(ChartOfAccountsRepository coaRepo, MongoTemplate mongoTemplate) {
        this.coaRepo = coaRepo;
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void initialise() {
        log.info("Initialising accounting-service collections");
        seedChartOfAccounts();
        createJournalProjectionIndexes();
        createInboxIndexes();
        log.info("accounting-service collections ready");
    }

    private void seedChartOfAccounts() {
        Instant now = Instant.now();
        List<ChartOfAccountsDocument> seeds = List.of(
                new ChartOfAccountsDocument("1000", "Customer Cash Holdings",       "ASSET",     "DEBIT",  null, true, "USD", now),
                new ChartOfAccountsDocument("1100", "Platform Float",               "ASSET",     "DEBIT",  null, true, "USD", now),
                new ChartOfAccountsDocument("2100", "Customer Wallet Liability",    "LIABILITY", "CREDIT", null, true, "USD", now),
                new ChartOfAccountsDocument("3000", "Retained Earnings",            "EQUITY",    "CREDIT", null, true, "USD", now),
                new ChartOfAccountsDocument("4000", "Fee Income",                   "REVENUE",   "CREDIT", null, true, "USD", now),
                new ChartOfAccountsDocument("4100", "FX Spread Income",             "REVENUE",   "CREDIT", null, true, "USD", now),
                new ChartOfAccountsDocument("5000", "Operating Expenses",           "EXPENSE",   "DEBIT",  null, true, "USD", now),
                new ChartOfAccountsDocument("5100", "Bad Debt Write-offs",          "EXPENSE",   "DEBIT",  null, true, "USD", now));

        int inserted = 0;
        for (ChartOfAccountsDocument doc : seeds) {
            if (!coaRepo.existsById(doc.getId())) {
                coaRepo.save(doc);
                inserted++;
            }
        }
        log.info("chart_of_accounts seeded: {} new system accounts (total {} now)", inserted, coaRepo.count());
    }

    private void createJournalProjectionIndexes() {
        var idx = mongoTemplate.indexOps(JournalEntryDocument.class);
        idx.createIndex(new Index().on("transactionId", Sort.Direction.ASC));
        idx.createIndex(new Index().on("account", Sort.Direction.ASC).on("postedAt", Sort.Direction.DESC));
        idx.createIndex(new Index().on("coaAccount", Sort.Direction.ASC).on("postedAt", Sort.Direction.DESC));
        idx.createIndex(new Index().on("postedAt", Sort.Direction.ASC));
    }

    private void createInboxIndexes() {
        var idx = mongoTemplate.indexOps(ProcessedEventDocument.class);
        idx.createIndex(new Index().on("processedAt", Sort.Direction.ASC));
        idx.createIndex(new Index().on("expireAt", Sort.Direction.ASC).expire(0L));
    }
}
