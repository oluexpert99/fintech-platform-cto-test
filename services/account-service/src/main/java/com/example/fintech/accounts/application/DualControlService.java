package com.example.fintech.accounts.application;

import com.example.fintech.accounts.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.persistence.document.PendingApprovalDocument;
import com.example.fintech.accounts.persistence.repository.PendingApprovalRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class DualControlService {
    private final PendingApprovalRepository pendingApprovalRepository;

    public DualControlService(PendingApprovalRepository pendingApprovalRepository) {
        this.pendingApprovalRepository = pendingApprovalRepository;
    }

    public void validateAndConsume(String operatorId, String accountId, StatusReason reason, String approverId) {
        if (approverId == null || approverId.isBlank()) {
            throw new OperatorApprovalRequiredException("Dual control approverId is required");
        }
        if (operatorId.equals(approverId)) {
            throw new OperatorApprovalRequiredException("Approver must be different from acting operator");
        }
        PendingApprovalDocument approval = pendingApprovalRepository
                .findByAccountIdAndApproverIdAndReasonAndStatus(accountId, approverId, reason, "PENDING")
                .orElseThrow(() -> new OperatorApprovalRequiredException("No valid pending approval found"));
        if (approval.getUsedAt() != null) {
            throw new OperatorApprovalRequiredException("Approval has already been consumed");
        }
        if (approval.getExpiresAt() == null || !approval.getExpiresAt().isAfter(Instant.now())) {
            throw new OperatorApprovalRequiredException("Approval is expired");
        }
        approval.setStatus("USED");
        approval.setUsedAt(Instant.now());
        pendingApprovalRepository.save(approval);
    }
}
