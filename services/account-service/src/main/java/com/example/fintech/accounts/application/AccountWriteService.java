package com.example.fintech.accounts.application;

import com.example.fintech.accounts.api.dto.AccountResponse;
import com.example.fintech.accounts.api.dto.OpenAccountRequest;
import com.example.fintech.accounts.api.dto.PatchAccountRequest;
import com.example.fintech.accounts.domain.exception.AccountNotFoundException;
import com.example.fintech.accounts.domain.exception.ForbiddenFieldEditException;
import com.example.fintech.accounts.domain.exception.IdempotencyConflictException;
import com.example.fintech.accounts.domain.exception.VersionConflictException;
import com.example.fintech.accounts.domain.model.AccountId;
import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.domain.policy.FieldEditPolicy;
import com.example.fintech.accounts.domain.policy.StatusTransitionPolicy;
import com.example.fintech.accounts.messaging.envelope.EventEnvelopeBuilder;
import com.example.fintech.accounts.messaging.event.AccountOpenedEvent;
import com.example.fintech.accounts.messaging.event.AccountStatusChangedEvent;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import com.example.fintech.accounts.persistence.document.OutboxRecordDocument;
import com.example.fintech.accounts.persistence.mapper.AccountMapper;
import com.example.fintech.accounts.persistence.repository.AccountRepository;
import com.example.fintech.accounts.persistence.repository.OutboxRepository;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
public class AccountWriteService {
    private final AccountRepository accountRepository;
    private final OutboxRepository outboxRepository;
    private final AccountMapper mapper;
    private final IdempotencyService idempotencyService;
    private final FieldEditPolicy fieldEditPolicy;
    private final StatusTransitionPolicy statusTransitionPolicy;
    private final EventEnvelopeBuilder envelopeBuilder;
    private final DualControlService dualControlService;

    public AccountWriteService(AccountRepository accountRepository,
                               OutboxRepository outboxRepository,
                               AccountMapper mapper,
                               IdempotencyService idempotencyService,
                               FieldEditPolicy fieldEditPolicy,
                               StatusTransitionPolicy statusTransitionPolicy,
                               EventEnvelopeBuilder envelopeBuilder,
                               DualControlService dualControlService) {
        this.accountRepository = accountRepository;
        this.outboxRepository = outboxRepository;
        this.mapper = mapper;
        this.idempotencyService = idempotencyService;
        this.fieldEditPolicy = fieldEditPolicy;
        this.statusTransitionPolicy = statusTransitionPolicy;
        this.envelopeBuilder = envelopeBuilder;
        this.dualControlService = dualControlService;
    }

    @Transactional
    public AccountResponse open(String caller, String key, OpenAccountRequest req) {
        String scopedKey = idempotencyService.scopedKey(caller, "open-account", key);
        String payloadHash = idempotencyService.payloadHash(req);
        AccountDocument existing = idempotencyService.findExisting(scopedKey).orElse(null);
        if (existing != null) {
            if (payloadHash.equals(existing.getPayloadHash())) {
                return mapper.toResponse(existing);
            }
            throw new IdempotencyConflictException();
        }

        Instant now = Instant.now();
        AccountDocument account = new AccountDocument();
        account.setId(AccountId.generate().value());
        account.setOwnerUserId(caller);
        account.setCurrency(req.currency());
        account.setType(req.type());
        account.setLabel(req.label());
        account.setBalance(0L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setStatusReason(StatusReason.USER_REQUESTED);
        account.setVersion(1L);
        account.setIdempotencyKey(scopedKey);
        account.setPayloadHash(payloadHash);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountRepository.save(account);

        AccountOpenedEvent event = new AccountOpenedEvent(account.getId(), caller, account.getCurrency(), account.getType(), account.getLabel(), account.getStatus());
        OutboxRecordDocument outbox = new OutboxRecordDocument();
        outbox.setAggregateId(account.getId());
        outbox.setTopic("accounts.account.opened");
        outbox.setEventId(UlidCreator.getMonotonicUlid().toString());
        outbox.setPayload(envelopeBuilder.envelope("accounts.account.opened", event));
        outbox.setStatus("PENDING");
        outbox.setAttempts(0);
        outbox.setLeaseUntil(Instant.EPOCH);
        outbox.setCreatedAt(now);
        outboxRepository.save(outbox);
        return mapper.toResponse(account);
    }

    @Transactional
    public AccountResponse patch(String caller, Set<String> roles, AccountId accountId, String key, Long ifMatchVersion, PatchAccountRequest req) {
        String scopedKey = idempotencyService.scopedKey(caller, "patch-account:" + accountId.value(), key);
        String payloadHash = idempotencyService.payloadHash(req);
        AccountDocument existingIdempotent = idempotencyService.findExisting(scopedKey).orElse(null);
        if (existingIdempotent != null) {
            if (payloadHash.equals(existingIdempotent.getPayloadHash())) {
                return mapper.toResponse(existingIdempotent);
            }
            throw new IdempotencyConflictException();
        }

        AccountDocument account = accountRepository.findById(accountId.value()).orElseThrow(() -> new AccountNotFoundException(accountId));
        boolean operator = roles.contains("operator");
        if (!caller.equals(account.getOwnerUserId()) && !operator) {
            throw new ForbiddenFieldEditException("account");
        }
        if (ifMatchVersion != null && account.getVersion() != ifMatchVersion) {
            throw new VersionConflictException(ifMatchVersion, account.getVersion());
        }

        fieldEditPolicy.check(account, caller, roles, req);
        long currentVersion = account.getVersion();
        AccountStatus previous = account.getStatus();
        if (req.status() != null) {
            statusTransitionPolicy.check(account, roles, req.status(), req.reason(), req.approverId());
            if (account.getStatus() == AccountStatus.FROZEN
                    && req.status() == AccountStatus.ACTIVE
                    && (req.reason() == StatusReason.FRAUD_SUSPECTED || req.reason() == StatusReason.COMPLIANCE_HOLD)) {
                dualControlService.validateAndConsume(caller, account.getId(), req.reason(), req.approverId());
            }
            account.setStatus(req.status());
            account.setStatusReason(req.reason());
            if (req.status() == AccountStatus.FROZEN) {
                account.setFrozenAt(Instant.now());
            }
            if (req.status() == AccountStatus.CLOSED) {
                account.setClosedAt(Instant.now());
            }
        }
        if (req.label() != null) {
            account.setLabel(req.label());
        }
        account.setVersion(account.getVersion() + 1);
        account.setUpdatedAt(Instant.now());
        account.setIdempotencyKey(scopedKey);
        account.setPayloadHash(payloadHash);
        account = accountRepository.updateIfVersionMatches(account, currentVersion)
                .orElseThrow(() -> new VersionConflictException(currentVersion, currentVersion + 1));

        if (req.status() != null && previous != req.status()) {
            AccountStatusChangedEvent event = new AccountStatusChangedEvent(account.getId(), previous, account.getStatus(), req.reason());
            OutboxRecordDocument outbox = new OutboxRecordDocument();
            outbox.setAggregateId(account.getId());
            outbox.setTopic("accounts.account.status-changed");
            outbox.setEventId(UlidCreator.getMonotonicUlid().toString());
            outbox.setPayload(envelopeBuilder.envelope("accounts.account.status-changed", event));
            outbox.setStatus("PENDING");
            outbox.setAttempts(0);
            outbox.setLeaseUntil(Instant.EPOCH);
            outbox.setCreatedAt(Instant.now());
            outboxRepository.save(outbox);
        }
        return mapper.toResponse(account);
    }
}
