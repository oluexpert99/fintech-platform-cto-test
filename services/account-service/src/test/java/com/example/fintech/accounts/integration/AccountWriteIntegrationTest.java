package com.example.fintech.accounts.integration;

import com.example.fintech.accounts.api.dto.AccountResponse;
import com.example.fintech.accounts.api.dto.OpenAccountRequest;
import com.example.fintech.accounts.api.dto.PatchAccountRequest;
import com.example.fintech.accounts.application.AccountWriteService;
import com.example.fintech.accounts.domain.exception.IdempotencyConflictException;
import com.example.fintech.accounts.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.accounts.domain.model.AccountId;
import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.AccountType;
import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import com.example.fintech.accounts.persistence.document.OutboxRecordDocument;
import com.example.fintech.accounts.persistence.document.PendingApprovalDocument;
import com.example.fintech.accounts.persistence.repository.AccountRepository;
import com.example.fintech.accounts.persistence.repository.OutboxRepository;
import com.example.fintech.accounts.persistence.repository.PendingApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountWriteIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AccountWriteService accountWriteService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private PendingApprovalRepository pendingApprovalRepository;
    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void resetCollections() {
        mongoTemplate.dropCollection(AccountDocument.class);
        mongoTemplate.dropCollection(OutboxRecordDocument.class);
        mongoTemplate.dropCollection(PendingApprovalDocument.class);
    }

    @Test
    void openAccount_happyPath_persistsAccountAndOutbox() {
        OpenAccountRequest request = new OpenAccountRequest("USD", AccountType.CHECKING, "Main");
        AccountResponse response = accountWriteService.open("U-ALICE", UUID.randomUUID().toString(), request);

        assertThat(response.id()).isNotBlank();
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        OutboxRecordDocument outbox = outboxRepository.findAll().getFirst();
        assertThat(outbox.getTopic()).isEqualTo("accounts.account.opened");
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void openAccount_sameKeySamePayload_isIdempotent() {
        String key = UUID.randomUUID().toString();
        OpenAccountRequest request = new OpenAccountRequest("USD", AccountType.CHECKING, "Main");

        AccountResponse first = accountWriteService.open("U-ALICE", key, request);
        AccountResponse second = accountWriteService.open("U-ALICE", key, request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void openAccount_sameKeyDifferentPayload_conflicts() {
        String key = UUID.randomUUID().toString();
        accountWriteService.open("U-ALICE", key, new OpenAccountRequest("USD", AccountType.CHECKING, "Main"));

        assertThatThrownBy(() -> accountWriteService.open("U-ALICE", key, new OpenAccountRequest("USD", AccountType.CHECKING, "Savings")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void patchStatus_toFrozen_emitsStatusChangedOutbox() {
        AccountResponse opened = accountWriteService.open("U-ALICE", UUID.randomUUID().toString(), new OpenAccountRequest("USD", AccountType.CHECKING, "Main"));

        AccountResponse patched = accountWriteService.patch(
                "U-ALICE",
                Set.of(),
                AccountId.of(opened.id()),
                UUID.randomUUID().toString(),
                opened.version(),
                new PatchAccountRequest(null, AccountStatus.FROZEN, StatusReason.USER_REQUESTED, null));

        assertThat(patched.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(outboxRepository.findAll()).hasSize(2);
        assertThat(outboxRepository.findAll().stream().anyMatch(o -> "accounts.account.status-changed".equals(o.getTopic()))).isTrue();
    }

    @Test
    void sensitiveUnfreeze_withoutApproval_isRejected() {
        AccountResponse opened = accountWriteService.open("U-ALICE", UUID.randomUUID().toString(), new OpenAccountRequest("USD", AccountType.CHECKING, "Main"));
        accountWriteService.patch("U-ALICE", Set.of(), AccountId.of(opened.id()), UUID.randomUUID().toString(), opened.version(),
                new PatchAccountRequest(null, AccountStatus.FROZEN, StatusReason.FRAUD_SUSPECTED, null));

        AccountDocument frozen = accountRepository.findById(opened.id()).orElseThrow();
        assertThatThrownBy(() -> accountWriteService.patch(
                "U-OP-1",
                Set.of("operator"),
                AccountId.of(opened.id()),
                UUID.randomUUID().toString(),
                frozen.getVersion(),
                new PatchAccountRequest(null, AccountStatus.ACTIVE, StatusReason.FRAUD_SUSPECTED, null)))
                .isInstanceOf(OperatorApprovalRequiredException.class);
    }

    @Test
    void sensitiveUnfreeze_withApproval_succeedsAndConsumesApproval() {
        AccountResponse opened = accountWriteService.open("U-ALICE", UUID.randomUUID().toString(), new OpenAccountRequest("USD", AccountType.CHECKING, "Main"));
        accountWriteService.patch("U-ALICE", Set.of(), AccountId.of(opened.id()), UUID.randomUUID().toString(), opened.version(),
                new PatchAccountRequest(null, AccountStatus.FROZEN, StatusReason.FRAUD_SUSPECTED, null));

        PendingApprovalDocument approval = new PendingApprovalDocument();
        approval.setAccountId(opened.id());
        approval.setApproverId("U-OP-2");
        approval.setReason(StatusReason.FRAUD_SUSPECTED);
        approval.setStatus("PENDING");
        approval.setCreatedAt(Instant.now());
        approval.setExpiresAt(Instant.now().plusSeconds(600));
        pendingApprovalRepository.save(approval);

        AccountDocument frozen = accountRepository.findById(opened.id()).orElseThrow();
        AccountResponse unfrozen = accountWriteService.patch(
                "U-OP-1",
                Set.of("operator"),
                AccountId.of(opened.id()),
                UUID.randomUUID().toString(),
                frozen.getVersion(),
                new PatchAccountRequest(null, AccountStatus.ACTIVE, StatusReason.FRAUD_SUSPECTED, "U-OP-2"));

        assertThat(unfrozen.status()).isEqualTo(AccountStatus.ACTIVE);
        PendingApprovalDocument used = pendingApprovalRepository.findById(approval.getId()).orElseThrow();
        assertThat(used.getStatus()).isEqualTo("USED");
        assertThat(used.getUsedAt()).isNotNull();
    }

    @Test
    void sensitiveUnfreeze_withExpiredApproval_isRejected() {
        AccountResponse opened = accountWriteService.open("U-ALICE", UUID.randomUUID().toString(), new OpenAccountRequest("USD", AccountType.CHECKING, "Main"));
        accountWriteService.patch("U-ALICE", Set.of(), AccountId.of(opened.id()), UUID.randomUUID().toString(), opened.version(),
                new PatchAccountRequest(null, AccountStatus.FROZEN, StatusReason.FRAUD_SUSPECTED, null));

        PendingApprovalDocument approval = new PendingApprovalDocument();
        approval.setAccountId(opened.id());
        approval.setApproverId("U-OP-2");
        approval.setReason(StatusReason.FRAUD_SUSPECTED);
        approval.setStatus("PENDING");
        approval.setCreatedAt(Instant.now().minusSeconds(1200));
        approval.setExpiresAt(Instant.now().minusSeconds(60));
        pendingApprovalRepository.save(approval);

        AccountDocument frozen = accountRepository.findById(opened.id()).orElseThrow();
        assertThatThrownBy(() -> accountWriteService.patch(
                "U-OP-1",
                Set.of("operator"),
                AccountId.of(opened.id()),
                UUID.randomUUID().toString(),
                frozen.getVersion(),
                new PatchAccountRequest(null, AccountStatus.ACTIVE, StatusReason.FRAUD_SUSPECTED, "U-OP-2")))
                .isInstanceOf(OperatorApprovalRequiredException.class)
                .hasMessageContaining("expired");
    }
}
