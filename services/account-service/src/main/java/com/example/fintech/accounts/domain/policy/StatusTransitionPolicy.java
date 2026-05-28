package com.example.fintech.accounts.domain.policy;

import com.example.fintech.accounts.domain.exception.InvalidStateTransitionException;
import com.example.fintech.accounts.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class StatusTransitionPolicy {
    public void check(AccountDocument account, Set<String> roles, AccountStatus target, StatusReason reason, String approverId) {
        AccountStatus from = account.getStatus();
        boolean operator = roles.contains("operator");

        if (from == AccountStatus.CLOSED) {
            throw new InvalidStateTransitionException(from, target, "closed is terminal");
        }
        if (target == null || target == from) {
            return;
        }

        if (from == AccountStatus.ACTIVE && target == AccountStatus.FROZEN) {
            return;
        }
        if (from == AccountStatus.ACTIVE && target == AccountStatus.CLOSED) {
            if (account.getBalance() != 0) {
                throw new InvalidStateTransitionException(from, target, "balance must be zero");
            }
            if (operator) {
                throw new OperatorApprovalRequiredException("Only owner can close active accounts");
            }
            return;
        }
        if (from == AccountStatus.FROZEN && target == AccountStatus.ACTIVE) {
            if (!operator) {
                throw new OperatorApprovalRequiredException("Only operators can unfreeze accounts");
            }
            if (reason == StatusReason.FRAUD_SUSPECTED || reason == StatusReason.COMPLIANCE_HOLD) {
                if (approverId == null || approverId.isBlank()) {
                    throw new OperatorApprovalRequiredException("Dual control approverId is required for sensitive unfreeze");
                }
            }
            return;
        }
        if (from == AccountStatus.FROZEN && target == AccountStatus.CLOSED) {
            if (!operator) {
                throw new OperatorApprovalRequiredException("Only operators can close frozen accounts");
            }
            return;
        }
        throw new InvalidStateTransitionException(from, target, "transition is not allowed");
    }
}
