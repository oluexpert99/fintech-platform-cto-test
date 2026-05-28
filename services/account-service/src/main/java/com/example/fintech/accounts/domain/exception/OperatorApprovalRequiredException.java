package com.example.fintech.accounts.domain.exception;

import java.util.Map;

public final class OperatorApprovalRequiredException extends DomainException {
    public OperatorApprovalRequiredException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "OPERATOR_APPROVAL_REQUIRED";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of();
    }
}
