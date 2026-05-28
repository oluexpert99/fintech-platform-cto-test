package com.example.fintech.accounts.unit;

import com.example.fintech.accounts.api.dto.PatchAccountRequest;
import com.example.fintech.accounts.domain.exception.ForbiddenFieldEditException;
import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.domain.policy.FieldEditPolicy;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEditPolicyTest {
    private final FieldEditPolicy policy = new FieldEditPolicy();

    @Test
    void labelChange_byOwner_isAllowed() {
        AccountDocument account = account("user-1");
        PatchAccountRequest req = new PatchAccountRequest("new-label", null, null, null);
        assertThatCode(() -> policy.check(account, "user-1", Set.of(), req)).doesNotThrowAnyException();
    }

    @Test
    void labelChange_byNonOwner_isRejected() {
        AccountDocument account = account("user-1");
        PatchAccountRequest req = new PatchAccountRequest("new-label", null, null, null);
        assertThatThrownBy(() -> policy.check(account, "user-2", Set.of("operator"), req))
                .isInstanceOf(ForbiddenFieldEditException.class);
    }

    @Test
    void statusChange_requiresReason() {
        AccountDocument account = account("user-1");
        PatchAccountRequest req = new PatchAccountRequest(null, AccountStatus.FROZEN, null, null);
        assertThatThrownBy(() -> policy.check(account, "user-1", Set.of(), req))
                .isInstanceOf(ForbiddenFieldEditException.class);
    }

    @Test
    void emptyPatch_isRejected() {
        AccountDocument account = account("user-1");
        PatchAccountRequest req = new PatchAccountRequest(null, null, null, null);
        assertThatThrownBy(() -> policy.check(account, "user-1", Set.of(), req))
                .isInstanceOf(ForbiddenFieldEditException.class);
    }

    @Test
    void statusWithReason_isAllowed() {
        AccountDocument account = account("user-1");
        PatchAccountRequest req = new PatchAccountRequest(null, AccountStatus.FROZEN, StatusReason.USER_REQUESTED, null);
        assertThatCode(() -> policy.check(account, "user-1", Set.of(), req)).doesNotThrowAnyException();
    }

    private static AccountDocument account(String ownerId) {
        AccountDocument doc = new AccountDocument();
        doc.setOwnerUserId(ownerId);
        return doc;
    }
}
