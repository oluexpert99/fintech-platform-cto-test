package com.example.fintech.accounts.unit;

import com.example.fintech.accounts.application.DualControlService;
import com.example.fintech.accounts.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.persistence.document.PendingApprovalDocument;
import com.example.fintech.accounts.persistence.repository.PendingApprovalRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DualControlServiceTest {

    private final PendingApprovalRepository repo = mock(PendingApprovalRepository.class);
    private final DualControlService service = new DualControlService(repo);

    @Test
    void rejectsWhenApproverMissing() {
        assertThatThrownBy(() -> service.validateAndConsume("op-1", "ACC1", StatusReason.FRAUD_SUSPECTED, null))
                .isInstanceOf(OperatorApprovalRequiredException.class);
    }

    @Test
    void rejectsWhenApproverEqualsOperator() {
        assertThatThrownBy(() -> service.validateAndConsume("op-1", "ACC1", StatusReason.FRAUD_SUSPECTED, "op-1"))
                .isInstanceOf(OperatorApprovalRequiredException.class);
    }

    @Test
    void rejectsExpiredApproval() {
        PendingApprovalDocument approval = new PendingApprovalDocument();
        approval.setAccountId("ACC1");
        approval.setApproverId("op-2");
        approval.setReason(StatusReason.FRAUD_SUSPECTED);
        approval.setStatus("PENDING");
        approval.setExpiresAt(Instant.now().minusSeconds(10));
        when(repo.findByAccountIdAndApproverIdAndReasonAndStatus("ACC1", "op-2", StatusReason.FRAUD_SUSPECTED, "PENDING"))
                .thenReturn(Optional.of(approval));

        assertThatThrownBy(() -> service.validateAndConsume("op-1", "ACC1", StatusReason.FRAUD_SUSPECTED, "op-2"))
                .isInstanceOf(OperatorApprovalRequiredException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void acceptsValidApproval() {
        PendingApprovalDocument approval = new PendingApprovalDocument();
        approval.setAccountId("ACC1");
        approval.setApproverId("op-2");
        approval.setReason(StatusReason.FRAUD_SUSPECTED);
        approval.setStatus("PENDING");
        approval.setExpiresAt(Instant.now().plusSeconds(600));
        when(repo.findByAccountIdAndApproverIdAndReasonAndStatus("ACC1", "op-2", StatusReason.FRAUD_SUSPECTED, "PENDING"))
                .thenReturn(Optional.of(approval));

        assertThatCode(() -> service.validateAndConsume("op-1", "ACC1", StatusReason.FRAUD_SUSPECTED, "op-2"))
                .doesNotThrowAnyException();
    }
}
