package com.example.fintech.transactions.domain.exception;

import java.util.Map;

/**
 * Sealed base for every business-rule failure in this service.
 *
 * <p>Subtypes carry typed fields (never just a String message) so {@code ProblemExceptionHandler}
 * can populate the RFC-7807 response's {@code params} object from typed accessors — no string
 * parsing, no losing structure on the wire.
 *
 * <p>The reflective test in {@code src/test/.../api/ProblemMappingTest.java} iterates this sealed
 * hierarchy and asserts every subtype has a mapping in {@code ProblemExceptionHandler}.
 */
public sealed abstract class DomainException extends RuntimeException
        permits AccountNotFoundException,
                AccountUnavailableException,
                CurrencyMismatchException,
                IdempotencyConflictException,
                IdempotencyInProgressException,
                InsufficientFundsException,
                LimitExceededException,
                MissingIdempotencyKeyException,
                OperatorApprovalRequiredException,
                OriginalTransactionNotReversibleException,
                SelfTransferException,
                TransactionNotFoundException {
    // StepUpRequiredException removed per ADR-0006: MFA / step-up auth is out of scope for the
    // test deliverable. The previous implementation always threw on amount > threshold, providing
    // no real security (it was effectively a hard ceiling). Reintroduce when MFA flows ship.

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Stable, locale-independent error code. Matches the catalogue in {@code api.md} §3.
     */
    public abstract String code();

    /**
     * Values for interpolation into the locale-bundle template the frontend renders.
     * Returns an immutable map; subclasses populate from their typed fields.
     */
    public abstract Map<String, Object> params();
}
