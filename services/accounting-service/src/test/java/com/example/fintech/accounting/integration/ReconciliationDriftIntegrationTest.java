package com.example.fintech.accounting.integration;

import com.example.fintech.accounting.application.ReconciliationJob;
import com.example.fintech.accounting.application.TrialBalanceCalculator;
import com.example.fintech.accounting.persistence.document.JournalEntryDocument;
import com.example.fintech.accounting.persistence.repository.JournalEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the reconciliation job + trial-balance calculator actually fire on a drift.
 *
 * <p>This is the test the reviewers asked for in round two — it would have caught the
 * {@code TrialBalanceCalculator.JOURNAL = "journal"} regression that pointed at the wrong
 * collection. Now {@code TrialBalanceCalculator} resolves the collection name via
 * {@code MongoTemplate.getCollectionName(JournalEntryDocument.class)}, this test exercises the
 * actual path against a real Mongo.
 *
 * <p>Scenarios:
 * <ol>
 *   <li><strong>Balanced</strong> — equal debits and credits → {@code delta == 0}, gauge stays 0.</li>
 *   <li><strong>Drift</strong> — manually insert an unbalanced row → reconciliation detects
 *       {@code delta != 0}, the {@code accounting.reconciliation.delta} gauge fires.</li>
 * </ol>
 */
@SpringBootTest
@Testcontainers
@DisplayName("Reconciliation detects drift on the projection")
class ReconciliationDriftIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("fintech_accounting"));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired private JournalEntryRepository journalRepo;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private ReconciliationJob reconciliationJob;
    @Autowired private TrialBalanceCalculator calculator;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        mongoTemplate.dropCollection(JournalEntryDocument.class);
        // Clear cache so each test starts with a fresh aggregation.
        Objects.requireNonNull(cacheManager.getCache("trial-balance-summary")).clear();
    }

    @Test
    @DisplayName("balanced ledger: delta == 0 and gauge stays at 0")
    void balanced_journal_zero_delta() {
        // Two balanced rows: DR 100 / CR 100 on the same currency
        journalRepo.save(line("JL-PROJ-AAA-DR", "AAA", "ACC100001", "DEBIT",  100, "USD"));
        journalRepo.save(line("JL-PROJ-AAA-CR", "AAA", "ACC100002", "CREDIT", 100, "USD"));

        reconciliationJob.reconcile();

        Double delta = meterRegistry.find("accounting.reconciliation.delta").gauge().value();
        assertThat(delta).isZero();

        var summary = calculator.calculate(Instant.now(), "USD");
        assertThat(summary.totals().debits()).isEqualTo(100L);
        assertThat(summary.totals().credits()).isEqualTo(100L);
        assertThat(summary.totals().delta()).isZero();
    }

    @Test
    @DisplayName("DRIFT detected: an unbalanced row makes delta != 0 and the gauge fires")
    void unbalanced_journal_nonzero_delta_drives_gauge() {
        // Deliberately unbalanced: a DEBIT line with no matching CREDIT
        journalRepo.save(line("JL-PROJ-BBB-DR", "BBB", "ACC100001", "DEBIT",  500, "USD"));
        journalRepo.save(line("JL-PROJ-BBB-CR", "BBB", "ACC100002", "CREDIT", 400, "USD"));
        // delta = 500 - 400 = 100 → drift

        reconciliationJob.reconcile();

        var summary = calculator.calculate(Instant.now(), "USD");
        assertThat(summary.totals().debits()).isEqualTo(500L);
        assertThat(summary.totals().credits()).isEqualTo(400L);
        assertThat(summary.totals().delta())
                .as("reconciliation MUST detect the 100-unit drift — this is the regression test "
                        + "for the wrong-collection bug")
                .isEqualTo(100L);

        Double delta = meterRegistry.find("accounting.reconciliation.delta").gauge().value();
        assertThat(delta)
                .as("the reconciliation gauge must reflect the live drift; non-zero must alert")
                .isEqualTo(100.0);

        // And the drift outcome counter must have been incremented exactly once
        Double driftCount = meterRegistry.find("accounting.reconciliation.runs.total")
                .tag("outcome", "drift").counter().count();
        assertThat(driftCount).isEqualTo(1.0);
    }

    private JournalEntryDocument line(String id, String txId, String account, String side,
                                       long amount, String currency) {
        JournalEntryDocument d = new JournalEntryDocument();
        d.setId(id);
        d.setTransactionId("TX-" + txId);
        d.setTransactionType("TRANSFER");
        d.setAccount(account);
        d.setCoaAccount("2100." + account);
        d.setSide(side);
        d.setAmount(amount);
        d.setCurrency(currency);
        d.setPostedAt(Instant.now());
        d.setProjectedAt(Instant.now());
        return d;
    }
}
