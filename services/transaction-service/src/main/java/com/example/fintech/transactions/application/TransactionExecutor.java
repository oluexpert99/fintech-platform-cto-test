package com.example.fintech.transactions.application;

import com.example.fintech.transactions.api.dto.CreateTransactionRequest;
import com.example.fintech.transactions.api.dto.TransactionResponse;
import com.example.fintech.transactions.domain.exception.AccountNotFoundException;
import com.example.fintech.transactions.domain.exception.AccountUnavailableException;
import com.example.fintech.transactions.domain.exception.InsufficientFundsException;
import com.example.fintech.transactions.domain.exception.OriginalTransactionNotReversibleException;
import com.example.fintech.transactions.domain.model.AccountId;
import com.example.fintech.transactions.domain.model.JournalEntryId;
import com.example.fintech.transactions.domain.model.Side;
import com.example.fintech.transactions.domain.model.TransactionId;
import com.example.fintech.transactions.domain.model.TransactionStatus;
import com.example.fintech.transactions.domain.model.TransactionType;
import com.example.fintech.transactions.domain.model.UserId;
import com.example.fintech.transactions.domain.policy.CurrencyPolicy;
import com.example.fintech.transactions.domain.policy.TransferLimitsPolicy;
import com.example.fintech.transactions.messaging.envelope.EventEnvelope;
import com.example.fintech.transactions.messaging.envelope.EventEnvelopeBuilder;
import com.example.fintech.transactions.messaging.event.TransactionCompletedEvent;
import com.example.fintech.transactions.messaging.event.TransactionReversedEvent;
import com.example.fintech.transactions.persistence.document.AccountDocument;
import com.example.fintech.transactions.persistence.document.JournalEntryDocument;
import com.example.fintech.transactions.persistence.document.OutboxRecordDocument;
import com.example.fintech.transactions.persistence.document.TransactionDocument;
import com.example.fintech.transactions.persistence.mapper.TransactionMapper;
import com.example.fintech.transactions.persistence.repository.AccountRepository;
import com.example.fintech.transactions.persistence.repository.JournalEntryRepository;
import com.example.fintech.transactions.persistence.repository.OutboxRepository;
import com.example.fintech.transactions.persistence.repository.TransactionRepository;
import com.github.f4b6a3.ulid.UlidCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * The {@code @Transactional} chokepoint for the money path.
 *
 * <p>Lives in a separate Spring bean from {@link TransactionWriteService} so the
 * orchestrator's call into either {@link #executeTransfer} or {@link #executeReversal} crosses
 * the Spring proxy boundary. This is the fix for the original self-invocation defect that left
 * {@code @Transactional} silently disabled — calling {@code this.executeTransfer(...)} from the
 * same bean bypasses the CGLIB proxy and runs the multi-document write set without a Mongo session.
 *
 * <p>Every method that mutates the ledger lives here. There is no public method in this class
 * that doesn't open a Mongo transaction.
 *
 * <p>Chart-of-Accounts reference for user wallets is {@code "2100." + accountId} per
 * {@code specs/accounting-service.spec.md} §3.4 — populated here on every journal line.
 */
@Service
public class TransactionExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransactionExecutor.class);
    /** Chart-of-Accounts parent for user wallets — sub-accounts of 2100 Customer Wallet Liability. */
    private static final String WALLET_COA_PREFIX = "2100.";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxRepository outboxRepository;
    private final TransferLimitsPolicy limitsPolicy;
    private final CurrencyPolicy currencyPolicy;
    private final TransactionMapper transactionMapper;
    private final EventEnvelopeBuilder envelopeBuilder;
    private final ObjectMapper objectMapper;

    public TransactionExecutor(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            JournalEntryRepository journalEntryRepository,
            OutboxRepository outboxRepository,
            TransferLimitsPolicy limitsPolicy,
            CurrencyPolicy currencyPolicy,
            TransactionMapper transactionMapper,
            EventEnvelopeBuilder envelopeBuilder,
            ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.outboxRepository = outboxRepository;
        this.limitsPolicy = limitsPolicy;
        this.currencyPolicy = currencyPolicy;
        this.transactionMapper = transactionMapper;
        this.envelopeBuilder = envelopeBuilder;
        this.objectMapper = objectMapper;
    }

    // ============================================================================================
    // TRANSFER — spec §4.1
    // ============================================================================================

    @Transactional
    public TransactionResponse executeTransfer(UserId caller,
                                                String scopedKey,
                                                String payloadHash,
                                                AccountId source,
                                                AccountId destination,
                                                CreateTransactionRequest request) {
        AccountDocument src = accountRepository.findById(source.value())
                .orElseThrow(() -> new AccountNotFoundException(source));
        if (!src.getOwnerUserId().equals(caller.value())) {
            // Avoid enumeration: same response as not-found
            throw new AccountNotFoundException(source);
        }
        AccountDocument dst = accountRepository.findById(destination.value())
                .orElseThrow(() -> new AccountNotFoundException(destination));

        currencyPolicy.requireSameCurrency(request.currency(), src.getCurrency(), dst.getCurrency());
        limitsPolicy.check(caller, request.amount(), request.currency());

        if (!accountRepository.conditionalDebit(source, request.amount(), src.getVersion())) {
            AccountDocument reload = accountRepository.findById(source.value()).orElseThrow();
            if (!"ACTIVE".equals(reload.getStatus())) {
                throw new AccountUnavailableException(source, reload.getStatus());
            }
            if (reload.getBalance() < request.amount()) {
                throw new InsufficientFundsException(source, reload.getBalance(), request.amount(), src.getCurrency());
            }
            throw new OptimisticLockingFailureException("version conflict on " + source.value());
        }
        if (!accountRepository.conditionalCredit(destination, request.amount(), dst.getVersion())) {
            AccountDocument reload = accountRepository.findById(destination.value()).orElseThrow();
            if (!"ACTIVE".equals(reload.getStatus())) {
                throw new AccountUnavailableException(destination, reload.getStatus());
            }
            throw new OptimisticLockingFailureException("version conflict on " + destination.value());
        }

        TransactionId txId = TransactionId.generate();
        Instant now = Instant.now();
        JournalEntryDocument debitLine = journalLine(txId, source, Side.DEBIT, request.amount(), request.currency(), now);
        JournalEntryDocument creditLine = journalLine(txId, destination, Side.CREDIT, request.amount(), request.currency(), now);
        journalEntryRepository.saveAll(List.of(debitLine, creditLine));

        TransactionDocument txDoc = new TransactionDocument();
        txDoc.setId(txId.value());
        txDoc.setIdempotencyKey(scopedKey);
        txDoc.setPayloadHash(payloadHash);
        txDoc.setType(TransactionType.TRANSFER.name());
        txDoc.setStatus(TransactionStatus.COMPLETED.name());
        txDoc.setSourceAccount(source.value());
        txDoc.setDestinationAccount(destination.value());
        txDoc.setAmount(request.amount());
        txDoc.setCurrency(request.currency());
        txDoc.setDescription(request.description());
        txDoc.setJournalLineIds(List.of(debitLine.getId(), creditLine.getId()));
        txDoc.setCallerSub(caller.value());
        txDoc.setCreatedAt(now);
        txDoc.setCompletedAt(now);
        transactionRepository.insert(txDoc);

        TransactionCompletedEvent event = new TransactionCompletedEvent(
                txId.value(), TransactionType.TRANSFER.name(),
                source.value(), destination.value(),
                request.amount(), request.currency(), request.description(), now);
        outboxRepository.insert(buildOutboxRow(
                txId, TransactionCompletedEvent.TOPIC,
                envelopeBuilder.wrap(TransactionCompletedEvent.EVENT_TYPE, TransactionCompletedEvent.EVENT_VERSION, event, now),
                now));

        log.info("transfer completed tx={} src={} dst={} amount={} currency={}",
                txId.value(), source.value(), destination.value(), request.amount(), request.currency());

        return transactionMapper.toResponse(txDoc);
    }

    // ============================================================================================
    // REVERSAL — spec §4.2
    // ============================================================================================

    @Transactional
    public TransactionResponse executeReversal(UserId caller, String scopedKey, String payloadHash,
                                                CreateTransactionRequest request) {
        TransactionId originalId = TransactionId.of(request.correctsTransactionId());
        TransactionDocument original = transactionRepository.findById(originalId.value())
                .orElseThrow(() -> new OriginalTransactionNotReversibleException(originalId, "NOT_FOUND"));
        if (!TransactionStatus.COMPLETED.name().equals(original.getStatus())) {
            throw new OriginalTransactionNotReversibleException(originalId, original.getStatus());
        }
        if (TransactionType.REVERSAL.name().equals(original.getType())) {
            throw new OriginalTransactionNotReversibleException(originalId, "ALREADY_REVERSAL");
        }
        // Also reject if a REVERSAL of this transaction already exists — derived state without
        // mutating the original. See `TransactionRepository.existsByCorrectsTransactionId`.
        if (transactionRepository.existsByCorrectsTransactionId(originalId.value())) {
            throw new OriginalTransactionNotReversibleException(originalId, "ALREADY_REVERSED");
        }

        AccountId origSrc = AccountId.of(original.getSourceAccount());
        AccountId origDst = AccountId.of(original.getDestinationAccount());

        AccountDocument origSrcDoc = accountRepository.findById(origSrc.value())
                .orElseThrow(() -> new AccountNotFoundException(origSrc));
        AccountDocument origDstDoc = accountRepository.findById(origDst.value())
                .orElseThrow(() -> new AccountNotFoundException(origDst));

        if (!accountRepository.conditionalDebit(origDst, original.getAmount(), origDstDoc.getVersion())) {
            AccountDocument reload = accountRepository.findById(origDst.value()).orElseThrow();
            if (!"ACTIVE".equals(reload.getStatus())) {
                throw new AccountUnavailableException(origDst, reload.getStatus());
            }
            throw new OriginalTransactionNotReversibleException(originalId, "ORIGINAL_DESTINATION_FUNDS_UNAVAILABLE");
        }
        if (!accountRepository.conditionalCredit(origSrc, original.getAmount(), origSrcDoc.getVersion())) {
            AccountDocument reload = accountRepository.findById(origSrc.value()).orElseThrow();
            if (!"ACTIVE".equals(reload.getStatus())) {
                throw new AccountUnavailableException(origSrc, reload.getStatus());
            }
            throw new OptimisticLockingFailureException("version conflict on " + origSrc.value());
        }

        TransactionId revTxId = TransactionId.generate();
        Instant now = Instant.now();
        JournalEntryDocument debitLine = journalLine(revTxId, origDst, Side.DEBIT, original.getAmount(), original.getCurrency(), now);
        JournalEntryDocument creditLine = journalLine(revTxId, origSrc, Side.CREDIT, original.getAmount(), original.getCurrency(), now);
        journalEntryRepository.saveAll(List.of(debitLine, creditLine));

        // NOTE: we DO NOT mutate the original transaction's status. The fact of reversal is
        // derived from the existence of a REVERSAL transaction with correctsTransactionId pointing
        // back to the original. This preserves the immutable audit record of the original
        // transaction being COMPLETED at its original timestamp.

        TransactionDocument revDoc = new TransactionDocument();
        revDoc.setId(revTxId.value());
        revDoc.setIdempotencyKey(scopedKey);
        revDoc.setPayloadHash(payloadHash);
        revDoc.setType(TransactionType.REVERSAL.name());
        revDoc.setStatus(TransactionStatus.COMPLETED.name());
        revDoc.setSourceAccount(origDst.value());
        revDoc.setDestinationAccount(origSrc.value());
        revDoc.setAmount(original.getAmount());
        revDoc.setCurrency(original.getCurrency());
        revDoc.setJournalLineIds(List.of(debitLine.getId(), creditLine.getId()));
        revDoc.setCorrectsTransactionId(original.getId());
        revDoc.setReason(request.reason());
        revDoc.setApproverId(request.approverId());
        revDoc.setCallerSub(caller.value());
        revDoc.setCreatedAt(now);
        revDoc.setCompletedAt(now);
        transactionRepository.insert(revDoc);

        TransactionReversedEvent event = new TransactionReversedEvent(
                revTxId.value(), TransactionType.REVERSAL.name(), original.getId(),
                origDst.value(), origSrc.value(),
                original.getAmount(), original.getCurrency(), now, request.reason());
        outboxRepository.insert(buildOutboxRow(
                revTxId, TransactionReversedEvent.TOPIC,
                envelopeBuilder.wrap(TransactionReversedEvent.EVENT_TYPE, TransactionReversedEvent.EVENT_VERSION, event, now),
                now));

        log.info("reversal completed reversalTx={} originalTx={} amount={} currency={}",
                revTxId.value(), original.getId(), original.getAmount(), original.getCurrency());

        return transactionMapper.toResponse(revDoc);
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private JournalEntryDocument journalLine(TransactionId txId, AccountId account, Side side,
                                              long amount, String currency, Instant postedAt) {
        JournalEntryDocument line = new JournalEntryDocument();
        line.setId(JournalEntryId.generate().value());
        line.setTransactionId(txId.value());
        line.setAccount(account.value());
        line.setCoaAccount(WALLET_COA_PREFIX + account.value());
        line.setSide(side.name());
        line.setAmount(amount);
        line.setCurrency(currency);
        line.setPostedAt(postedAt);
        return line;
    }

    private OutboxRecordDocument buildOutboxRow(TransactionId aggregateId, String topic,
                                                 EventEnvelope<?> envelope, Instant now) {
        OutboxRecordDocument outbox = new OutboxRecordDocument();
        outbox.setId("OB-" + UlidCreator.getUlid().toString());
        outbox.setAggregateId(aggregateId.value());
        outbox.setTopic(topic);
        outbox.setEventId(envelope.eventId());
        outbox.setPayload(objectMapper.convertValue(envelope, new TypeReference<Map<String, Object>>() {}));
        outbox.setStatus("PENDING");
        outbox.setAttempts(0);
        outbox.setLeaseUntil(Instant.EPOCH);
        outbox.setCreatedAt(now);
        // expireAt deliberately null until markSent — see SchemaInitializer partial TTL.
        outbox.setExpireAt(null);
        return outbox;
    }
}
