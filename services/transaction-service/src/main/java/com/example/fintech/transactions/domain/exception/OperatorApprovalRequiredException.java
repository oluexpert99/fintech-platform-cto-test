package com.example.fintech.transactions.domain.exception;

import java.util.Map;

public final class OperatorApprovalRequiredException extends DomainException {

    private final String reason;

    public OperatorApprovalRequiredException(String reason) {
        super("Operator approval required: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String code() {
        return "OPERATOR_APPROVAL_REQUIRED";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("reason", reason);
    }
}
