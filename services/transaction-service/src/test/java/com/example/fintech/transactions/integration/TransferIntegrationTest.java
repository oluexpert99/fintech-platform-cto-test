package com.example.fintech.transactions.integration;

import com.example.fintech.transactions.api.dto.CreateTransactionRequest;
import com.example.fintech.transactions.api.dto.TransactionResponse;
import com.example.fintech.transactions.application.TransactionWriteService;
import com.example.fintech.transactions.domain.exception.AccountUnavailableException;
import com.example.fintech.transactions.domain.exception.CurrencyMismatchException;
import com.example.fintech.transactions.domain.exception.IdempotencyConflictException;
import com.example.fintech.transactions.domain.exception.InsufficientFundsException;
import com.example.fintech.transactions.domain.exception.SelfTransferException;
import com.example.fintech.transactions.domain.model.TransactionStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration scenarios from {@code transaction-service.spec} §5.2 — happy path + key failures.
 *
 * <p>Runs against real Mongo + Kafka via {@link IntegrationTestBase}.
 */
@DisplayName("TransferService — integration")
class TransferIntegrationTest extends IntegrationTestBase {

    private static final String OWNER = "U-ALICE";
    private static final String OTHER_USER = "U-MALLORY";
    private static final String SOURCE = "ACC100001";
    private static final String DESTINATION = "ACC100002";

    @Autowired private TransactionWriteService writeService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void resetCollections() {
        // Per-test isolation: drop the collections we touch.
        mongoTemplate.dropCollection(AccountDocument.class);
        mongoTemplate.dropCollection(TransactionDocument.class);
        mongoTemplate.dropCollection(JournalEntryDocument.class);
        mongoTemplate.dropCollection(OutboxRecordDocument.class);
    }

    // ---------- Happy path ----------

    @Test
    @DisplayName("happy path: debit, credit, journal pair, outbox row")
    void happyPath() {
        seedAccount(SOURCE, OWNER, "USD", 10_000, "ACTIVE");
        seedAccount(DESTINATION, OTHER_USER, "USD", 0, "ACTIVE");

        CreateTransactionRequest request = transferRequest(SOURCE, DESTINATION, 250, "USD", "Coffee");
        String key = UUID.randomUUID().toString();

        TransactionResponse response = writeService.create(UserId.of(OWNER), java.util.Set.of(), key, request);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(response.amount()).isEqualTo(250L);
        assertThat(response.journalLineIds()).hasSize(2);

        // Account balances moved
        AccountDocument src = accountRepository.findById(SOURCE).orElseThrow();
        AccountDocument dst = accountRepository.findById(DESTINATION).orElseThrow();
        assertThat(src.getBalance()).isEqualTo(9_750L);
        assertThat(dst.getBalance()).isEqualTo(250L);

        // Journal: exactly two lines, debit on src, credit on dst, same transactionId
        List<JournalEntryDocument> lines = journalEntryRepository.findByTransactionId(response.transactionId());
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().filter(l -> "DEBIT".equals(l.getSide())).findFirst().orElseThrow().getAccount())
                .isEqualTo(SOURCE);
        assertThat(lines.stream().filter(l -> "CREDIT".equals(l.getSide())).findFirst().orElseThrow().getAccount())
                .isEqualTo(DESTINATION);

        // Outbox row inserted, pending
        List<OutboxRecordDocument> outbox = outboxRepository.findAll();
        assertThat(outbox).hasSize(1);
        OutboxRecordDocument row = outbox.get(0);
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getTopic()).isEqualTo("transactions.transfer.completed");
        assertThat(row.getAggregateId()).isEqualTo(response.transactionId());
    }

    // ---------- Idempotency ----------

    @Test
    @DisplayName("replay with same key + same payload returns the original response, no new writes")
    void idempotentReplaySamePayload() {
        seedAccount(SOURCE, OWNER, "USD", 10_000, "ACTIVE");
        seedAccount(DESTINATION, OTHER_USER, "USD", 0, "ACTIVE");

        CreateTransactionRequest request = transferRequest(SOURCE, DESTINATION, 100, "USD", "x");
        String key = UUID.randomUUID().toString();

        TransactionResponse first = writeService.create(UserId.of(OWNER), java.util.Set.of(), key, request);
        TransactionResponse second = writeService.create(UserId.of(OWNER), java.util.Set.of(), key, request);

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(transactionRepository.count()).isEqualTo(1L);
        assertThat(journalEntryRepository.count()).isEqualTo(2L);
        assertThat(outboxRepository.count()).isEqualTo(1L);

        AccountDocument src = accountRepository.findById(SOURCE).orElseThrow();
        assertThat(src.getBalance()).isEqualTo(9_900L);
    }

    @Test
    @DisplayName("same key + different payload returns 409 IDEMPOTENCY_KEY_CONFLICT")
    void idempotentConflict() {
        seedAccount(SOURCE, OWNER, "USD", 10_000, "ACTIVE");
        seedAccount(DESTINATION, OTHER_USER, "USD", 0, "ACTIVE");

        CreateTransactionRequest first = transferRequest(SOURCE, DESTINATION, 100, "USD", "a");
        CreateTransactionRequest second = transferRequest(SOURCE, DESTINATION, 200, "USD", "b");
        String key = UUID.randomUUID().toString();

        writeService.create(UserId.of(OWNER), java.util.Set.of(), key, first);
        assertThatThrownBy(() -> writeService.create(UserId.of(OWNER), java.util.Set.of(), key, second))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    // ---------- Failure branches ----------

    @Test
    @DisplayName("insufficient funds returns 422 with typed params; no journal lines, no outbox")
    void insufficientFunds() {
        seedAccount(SOURCE, OWNER, "USD", 50, "ACTIVE");
        seedAccount(DESTINATION, OTHER_USER, "USD", 0, "ACTIVE");

        CreateTransactionRequest request = transferRequest(SOURCE, DESTINATION, 100, "USD", "x");

        assertThatThrownBy(() -> writeService.create(UserId.of(OWNER), java.util.Set.of(), UUID.randomUUID().toString(), request))
                .isInstanceOfSatisfying(InsufficientFundsException.class, e -> {
                    assertThat(e.accountId().value()).isEqualTo(SOURCE);
                    assertThat(e.available()).isEqualTo(50L);
                    assertThat(e.requested()).isEqualTo(100L);
                    assertThat(e.currency()).isEqualTo("USD");
                });

        // No side effects
        assertThat(transactionRepository.count()).isZero();
        assertThat(journalEntryRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(accountRepository.findById(SOURCE).orElseThrow().getBalance()).isEqualTo(50L);
    }

    @Test
    @DisplayName("frozen source returns 422 ACCOUNT_UNAVAILABLE")
    void accountFrozen() {
        seedAccount(SOURCE, OWNER, "USD", 10_000, "FROZEN");
        seedAccount(DESTINATION, OTHER_USER, "USD", 0, "ACTIVE");

        CreateTransactionRequest request = transferRequest(SOURCE, DESTINATION, 100, "USD", "x");

        assertThatThrownBy(() -> writeService.create(UserId.of(OWNER), java.util.Set.of(), UUID.randomUUID().toString(), request))
                .isInstanceOfSatisfying(AccountUnavailableException.class, e -> {
                    assertThat(e.accountId().value()).isEqualTo(SOURCE);
                    assertThat(e.status()).isEqualTo("FROZEN");
                });
    }

    @Test
    @DisplayName("self-transfer caught pre-transaction")
    void selfTransfer() {
        seedAccount(SOURCE, OWNER, "USD", 10_000, "ACTIVE");

        CreateTransactionRequest request = transferRequest(SOURCE, SOURCE, 100, "USD", "x");

        assertThatThrownBy(() -> writeService.create(UserId.of(OWNER), java.util.Set.of(), UUID.randomUUID().toString(), request))
                .isInstanceOf(SelfTransferException.class);
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("currency mismatch returns 422")
    void currencyMismatch() {
        seedAccount(SOURCE, OWNER, "USD", 10_000, "ACTIVE");
        seedAccount(DESTINATION, OTHER_USER, "EUR", 0, "ACTIVE");

        CreateTransactionRequest request = transferRequest(SOURCE, DESTINATION, 100, "USD", "x");

        assertThatThrownBy(() -> writeService.create(UserId.of(OWNER), java.util.Set.of(), UUID.randomUUID().toString(), request))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    // ---------- Helpers ----------

    private void seedAccount(String id, String owner, String currency, long balance, String status) {
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

    private CreateTransactionRequest transferRequest(String source, String destination, long amount,
                                                     String currency, String description) {
        return new CreateTransactionRequest(
                TransactionType.TRANSFER, source, destination, amount, currency, description,
                null, null, null);
    }
}
