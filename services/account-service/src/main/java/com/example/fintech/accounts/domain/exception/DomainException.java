package com.example.fintech.accounts.domain.exception;

import java.util.Map;

public sealed abstract class DomainException extends RuntimeException
        permits AccountNotFoundException,
        ForbiddenFieldEditException,
        IdempotencyConflictException,
        IdempotencyInProgressException,
        InvalidStateTransitionException,
        MissingIdempotencyKeyException,
        OperatorApprovalRequiredException,
        VersionConflictException {

    protected DomainException(String message) {
        super(message);
    }

    public abstract String code();

    public abstract Map<String, Object> params();
}
