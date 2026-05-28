package com.example.fintech.accounts.persistence.repository;

import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.persistence.document.PendingApprovalDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PendingApprovalRepository extends MongoRepository<PendingApprovalDocument, String> {
    Optional<PendingApprovalDocument> findByAccountIdAndApproverIdAndReasonAndStatus(
            String accountId, String approverId, StatusReason reason, String status);
}
