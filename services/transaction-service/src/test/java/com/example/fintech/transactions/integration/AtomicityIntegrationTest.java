package com.example.fintech.transactions.integration;

import com.example.fintech.transactions.api.dto.CreateTransactionRequest;
import com.example.fintech.transactions.application.TransactionWriteService;
import com.example.fintech.transactions.domain.model.TransactionType;
import com.example.fintech.transactions.domain.model.UserId;
import com.example.fintech.transactions.persistence.document.AccountDocument;
import com.example.fintech.transactions.persistence.document.JournalEntryDocument;
import com.example.fintech.transactions.persistence.document.OutboxRecordDocument;
import com.example.fintech.transactions.persistence.document.TransactionDocument;
import com.example.fintech.transactions.persistence.repository.AccountRepository;
import com.example.fintech.transactions.persistence.repository.JournalEntryRepository;
import com.example.fintech.transactions.persistence.repository.OutboxRepository;
import com.example.fintech.transactions.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * The two tests promised in {@code transaction-service.spec} §5.2 that were missing:
 *
 * <ol>
 *   <li><strong>Atomicity:</strong> if the credit step fails after the debit succeeds, the
 *       Mongo transaction must roll back — source balance restored, no journal lines, no
 *       transactions row, no outbox row. This is the test that proves the {@code @Transactional}
 *       boundary actually works (previously broken by Spring-proxy self-invocation).</li>
 *   <li><strong>Concurrent same-key:</strong> 50 threads firing the same
 *       {@code Idempotency-Key} simultaneously. Exactly ONE transactions row must be persisted;
 *       the others either return the original (replay) or throw a DuplicateKey-derived 409.</li>
 * </ol>
 */
@DisplayName("Transactional atomicity + concurrent idempotency")
class AtomicityIntegrationTest extends IntegrationTestBase {

    private static final String OWNER = "U-ALICE";
    private static final String OTHER = "U-BOB";
    private static final String SOURCE = "ACC100001";
    private static final String DESTINATION = "ACC100002";

    @Autowired private TransactionWriteService writeService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private MongoTemplate mongoTemplate;

    /** Replaces the real repository with a spy so we can throw mid-transaction. */
    @MockitoSpyBean private AccountRepository accountRepositorySpy;

    @BeforeEach
    void reset() {
        mongoTemplate.dropCollection(AccountDocument.class);
        mongoTemplate.dropCollection(TransactionDocument.class);
        mongoTemplate.dropCollection(JournalEntryDocument.class);
        mongoTemplate.dropCollection(OutboxRecordDocument.class);
    }

    @Test
    @DisplayName("atomicity: credit-step failure rolls back the debit — no money is lost")
    void atomicity_creditFails_debitRollsBack() {
        seed(SOURCE, OWNER, "USD", 10_000, "ACTIVE");
        seed(DESTINATION, OTHER, "USD", 0, "ACTIVE");

        // Spy: make conditionalCredit blow up after conditionalDebit has already succeeded.
        doThrow(new RuntimeException("simulated catastrophe between debit and credit"))
                .when(accountRepositorySpy).conditionalCredit(any(), anyLong(), anyLong());

        CreateTransactionRequest request = transferRequest(100);
        assertThatThrownBy(() -> writeService.create(UserId.of(OWNER), Set.of(), UUID.randomUUID().toString(), request))
                .isInstanceOf(RuntimeException.class);

        // The whole transaction must have rolled back: balance unchanged, no journal, no tx, no outbox.
        AccountDocument src = accountRepository.findById(SOURCE).orElseThrow();
        assertThat(src.getBalance())
                .as("source balance must roll back to original value (10_000)")
                .isEqualTo(10_000L);

        assertThat(transactionRepository.count())
                .as("no transactions document should exist when the credit fails")
                .isZero();
        assertThat(journalEntryRepository.count())
                .as("no journal lines should be written when the credit fails")
                .isZero();
        assertThat(outboxRepository.count())
                .as("no outbox row should be written when the credit fails")
                .isZero();
    }

    @Test
    @DisplayName("concurrent same-key: 50 threads → exactly ONE transactions row")
    void concurrent_sameKey_exactlyOneTransactionPersisted() throws Exception {
        seed(SOURCE, OWNER, "USD", 1_000_000, "ACTIVE");
        seed(DESTINATION, OTHER, "USD", 0, "ACTIVE");

        CreateTransactionRequest request = transferRequest(100);
        String idempotencyKey = UUID.randomUUID().toString();
        int concurrency = 50;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Set<String> seenTransactionIds = java.util.Collections.synchronizedSet(new HashSet<>());

        try {
            CompletableFuture<?>[] futures = new CompletableFuture[concurrency];
            for (int i = 0; i < concurrency; i++) {
                futures[i] = CompletableFuture.runAsync(() -> {
                    try {
                        var response = writeService.create(UserId.of(OWNER), Set.of(), idempotencyKey, request);
                        successes.incrementAndGet();
                        if (response != null && response.transactionId() != null) {
                            seenTransactionIds.add(response.transactionId());
                        }
                    } catch (RuntimeException e) {
                        // Spring DAO exceptions or our typed IdempotencyConflict/InProgress — all OK
                        conflicts.incrementAndGet();
                    }
                }, pool);
            }
            CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        // Exactly one transactions row must exist
        long txCount = transactionRepository.count();
        assertThat(txCount)
                .as("exactly one transactions row across %d concurrent same-key calls (saw %d successes, %d conflicts)",
                        concurrency, successes.get(), conflicts.get())
                .isEqualTo(1L);

        // All successful callers saw the same transactionId
        assertThat(seenTransactionIds)
                .as("all replay calls must return the same canonical transactionId")
                .hasSize(1);

        // Account balances reflect exactly one transfer
        assertThat(accountRepository.findById(SOURCE).orElseThrow().getBalance()).isEqualTo(999_900L);
        assertThat(accountRepository.findById(DESTINATION).orElseThrow().getBalance()).isEqualTo(100L);

        // Exactly two journal lines (the DEBIT + CREDIT for the one tx) — not 100
        assertThat(journalEntryRepository.count()).isEqualTo(2L);

        // Exactly one outbox row
        assertThat(outboxRepository.count()).isEqualTo(1L);
    }

    // ---- helpers ----

    private void seed(String id, String owner, String currency, long balance, String status) {
        AccountDocument doc = new AccountDocument();
        doc.setId(id);
        doc.setOwnerUserId(owner);
        doc.setCurrency(currency);
        doc.setType("CHECKING");
        doc.setBalance(balance);
        doc.setStatus(status);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        doc.setVersion(0L);
        accountRepository.save(doc);
    }

    private CreateTransactionRequest transferRequest(long amount) {
        return new CreateTransactionRequest(
                TransactionType.TRANSFER, SOURCE, DESTINATION, amount, "USD", "atomicity test",
                null, null, null);
    }
}
