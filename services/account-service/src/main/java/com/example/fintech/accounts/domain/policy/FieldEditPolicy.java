package com.example.fintech.accounts.domain.policy;

import com.example.fintech.accounts.api.dto.PatchAccountRequest;
import com.example.fintech.accounts.domain.exception.ForbiddenFieldEditException;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class FieldEditPolicy {
    public void check(AccountDocument account, String caller, Set<String> roles, PatchAccountRequest req) {
        if (req.label() != null && !account.getOwnerUserId().equals(caller)) {
            throw new ForbiddenFieldEditException("label");
        }
        if (req.status() != null && req.reason() == null) {
            throw new ForbiddenFieldEditException("reason");
        }
        if (req.isEmpty()) {
            throw new ForbiddenFieldEditException("patch");
        }
    }
}
