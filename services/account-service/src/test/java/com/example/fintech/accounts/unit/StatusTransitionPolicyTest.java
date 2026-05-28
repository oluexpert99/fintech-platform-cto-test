package com.example.fintech.accounts.unit;

import com.example.fintech.accounts.domain.exception.InvalidStateTransitionException;
import com.example.fintech.accounts.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.domain.policy.StatusTransitionPolicy;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusTransitionPolicyTest {

    private final StatusTransitionPolicy policy = new StatusTransitionPolicy();

    @Test
    void activeToFrozen_allowsOwner() {
        AccountDocument account = account(AccountStatus.ACTIVE, 100);
        assertThatCode(() -> policy.check(account, Set.of(), AccountStatus.FROZEN, StatusReason.USER_REQUESTED, null))
                .doesNotThrowAnyException();
    }

    @Test
    void activeToClosed_rejectsNonZeroBalance() {
        AccountDocument account = account(AccountStatus.ACTIVE, 1);
        assertThatThrownBy(() -> policy.check(account, Set.of(), AccountStatus.CLOSED, StatusReason.USER_REQUESTED, null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void activeToClosed_rejectsOperator() {
        AccountDocument account = account(AccountStatus.ACTIVE, 0);
        assertThatThrownBy(() -> policy.check(account, Set.of("operator"), AccountStatus.CLOSED, StatusReason.OPERATOR_ACTION, null))
                .isInstanceOf(OperatorApprovalRequiredException.class);
    }

    @Test
    void frozenToActive_rejectsWithoutOperatorRole() {
        AccountDocument account = account(AccountStatus.FROZEN, 0);
        assertThatThrownBy(() -> policy.check(account, Set.of(), AccountStatus.ACTIVE, StatusReason.OPERATOR_ACTION, null))
                .isInstanceOf(OperatorApprovalRequiredException.class);
    }

    @Test
    void frozenToActive_rejectsSensitiveReason_withoutDualControl() {
        AccountDocument account = account(AccountStatus.FROZEN, 0);
        assertThatThrownBy(() -> policy.check(account, Set.of("operator"), AccountStatus.ACTIVE, StatusReason.FRAUD_SUSPECTED, null))
                .isInstanceOf(OperatorApprovalRequiredException.class);
    }

    @Test
    void frozenToActive_allowsOperatorForNonSensitiveReason() {
        AccountDocument account = account(AccountStatus.FROZEN, 0);
        assertThatCode(() -> policy.check(account, Set.of("operator"), AccountStatus.ACTIVE, StatusReason.OPERATOR_ACTION, "approver-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void frozenToClosed_requiresOperator() {
        AccountDocument account = account(AccountStatus.FROZEN, 0);
        assertThatThrownBy(() -> policy.check(account, Set.of(), AccountStatus.CLOSED, StatusReason.OPERATOR_ACTION, null))
                .isInstanceOf(OperatorApprovalRequiredException.class);
    }

    @Test
    void closedIsTerminal() {
        AccountDocument account = account(AccountStatus.CLOSED, 0);
        assertThatThrownBy(() -> policy.check(account, Set.of("operator"), AccountStatus.ACTIVE, StatusReason.OPERATOR_ACTION, null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    private static AccountDocument account(AccountStatus status, long balance) {
        AccountDocument doc = new AccountDocument();
        doc.setStatus(status);
        doc.setBalance(balance);
        return doc;
    }
}
