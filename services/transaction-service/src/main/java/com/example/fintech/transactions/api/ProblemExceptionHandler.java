package com.example.fintech.transactions.api;

import com.example.fintech.transactions.api.dto.ProblemResponse;
import com.example.fintech.transactions.domain.exception.AccountNotFoundException;
import com.example.fintech.transactions.domain.exception.AccountUnavailableException;
import com.example.fintech.transactions.domain.exception.CurrencyMismatchException;
import com.example.fintech.transactions.domain.exception.DomainException;
import com.example.fintech.transactions.domain.exception.IdempotencyConflictException;
import com.example.fintech.transactions.domain.exception.IdempotencyInProgressException;
import com.example.fintech.transactions.domain.exception.InsufficientFundsException;
import com.example.fintech.transactions.domain.exception.LimitExceededException;
import com.example.fintech.transactions.domain.exception.MissingIdempotencyKeyException;
import com.example.fintech.transactions.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.transactions.domain.exception.OriginalTransactionNotReversibleException;
import com.example.fintech.transactions.domain.exception.SelfTransferException;
import com.example.fintech.transactions.domain.exception.TransactionNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.*;

/**
 * Maps every {@link DomainException} subtype to an RFC-7807 Problem Detail response with our
 * {@code code} + {@code params} extension. See {@code transaction-service.spec} §4.6.
 *
 * <p>A reflective test in {@code src/test/.../api/ProblemMappingTest.java} iterates the
 * {@link DomainException} sealed hierarchy and asserts every subtype is mapped here.
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://api.example.com/problems/";
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    // ----- Domain exceptions (sealed hierarchy) -----

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<ProblemResponse> handle(MissingIdempotencyKeyException e, HttpServletRequest req) {
        return problem(e, BAD_REQUEST, "Idempotency-Key required", e.getMessage(), req);
    }

    @ExceptionHandler(SelfTransferException.class)
    public ResponseEntity<ProblemResponse> handle(SelfTransferException e, HttpServletRequest req) {
        return problem(e, UNPROCESSABLE_CONTENT, "Self-transfer not allowed", e.getMessage(), req);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ProblemResponse> handle(InsufficientFundsException e, HttpServletRequest req) {
        return problem(e, UNPROCESSABLE_CONTENT, "Insufficient funds", e.getMessage(), req);
    }

    @ExceptionHandler(AccountUnavailableException.class)
    public ResponseEntity<ProblemResponse> handle(AccountUnavailableException e, HttpServletRequest req) {
        return problem(e, UNPROCESSABLE_CONTENT, "Account unavailable", e.getMessage(), req);
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<ProblemResponse> handle(CurrencyMismatchException e, HttpServletRequest req) {
        return problem(e, UNPROCESSABLE_CONTENT, "Currency mismatch", e.getMessage(), req);
    }

    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<ProblemResponse> handle(LimitExceededException e, HttpServletRequest req) {
        return problem(e, UNPROCESSABLE_CONTENT, "Limit exceeded", e.getMessage(), req);
    }

    @ExceptionHandler(OriginalTransactionNotReversibleException.class)
    public ResponseEntity<ProblemResponse> handle(OriginalTransactionNotReversibleException e, HttpServletRequest req) {
        return problem(e, UNPROCESSABLE_CONTENT, "Original transaction not reversible", e.getMessage(), req);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ProblemResponse> handle(AccountNotFoundException e, HttpServletRequest req) {
        return problem(e, NOT_FOUND, "Resource not found", e.getMessage(), req);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ProblemResponse> handle(TransactionNotFoundException e, HttpServletRequest req) {
        return problem(e, NOT_FOUND, "Resource not found", e.getMessage(), req);
    }

    @ExceptionHandler(OperatorApprovalRequiredException.class)
    public ResponseEntity<ProblemResponse> handle(OperatorApprovalRequiredException e, HttpServletRequest req) {
        return problem(e, FORBIDDEN, "Operator approval required", e.getMessage(), req);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemResponse> handle(IdempotencyConflictException e, HttpServletRequest req) {
        return problem(e, CONFLICT, "Idempotency-Key conflict", e.getMessage(), req);
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ProblemResponse> handle(IdempotencyInProgressException e, HttpServletRequest req) {
        ResponseEntity<ProblemResponse> body = problem(e, CONFLICT, "Idempotency in progress", e.getMessage(), req);
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(body.getHeaders());
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        return new ResponseEntity<>(body.getBody(), headers, CONFLICT);
    }

    // ----- Framework exceptions -----

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<ProblemResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ProblemResponse.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "VALIDATION_FAILED",
                        fe.getDefaultMessage(),
                        Map.of()))
                .toList();
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + "validation-failed",
                "VALIDATION_FAILED",
                "Validation failed",
                BAD_REQUEST.value(),
                "One or more fields failed validation",
                Map.of(),
                req.getRequestURI(),
                MDC.get("correlationId"),
                Instant.now(),
                fieldErrors);
        return ResponseEntity.status(BAD_REQUEST).contentType(PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemResponse> handleMalformed(HttpMessageNotReadableException e, HttpServletRequest req) {
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + "malformed-request",
                "MALFORMED_REQUEST",
                "Malformed request",
                BAD_REQUEST.value(),
                "Request body could not be parsed",
                Map.of(),
                req.getRequestURI(),
                MDC.get("correlationId"),
                Instant.now(),
                null);
        return ResponseEntity.status(BAD_REQUEST).contentType(PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemResponse> handleAuth(AuthenticationException e, HttpServletRequest req) {
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + "unauthorized",
                "UNAUTHORIZED",
                "Unauthorized",
                UNAUTHORIZED.value(),
                "Authentication required",
                Map.of(),
                req.getRequestURI(),
                MDC.get("correlationId"),
                Instant.now(),
                null);
        return ResponseEntity.status(UNAUTHORIZED).contentType(PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemResponse> handleForbidden(AccessDeniedException e, HttpServletRequest req) {
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + "forbidden",
                "FORBIDDEN",
                "Forbidden",
                FORBIDDEN.value(),
                "Caller lacks required permission",
                Map.of(),
                req.getRequestURI(),
                MDC.get("correlationId"),
                Instant.now(),
                null);
        return ResponseEntity.status(FORBIDDEN).contentType(PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemResponse> handleOptimisticLock(OptimisticLockingFailureException e, HttpServletRequest req) {
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + "version-conflict",
                "VERSION_CONFLICT",
                "Version conflict",
                CONFLICT.value(),
                "Resource was modified concurrently",
                Map.of(),
                req.getRequestURI(),
                MDC.get("correlationId"),
                Instant.now(),
                null);
        return ResponseEntity.status(CONFLICT).contentType(PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> handleAny(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception", e);
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + "internal",
                "INTERNAL",
                "Internal server error",
                INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred",
                Map.of(),
                req.getRequestURI(),
                MDC.get("correlationId"),
                Instant.now(),
                null);
        return ResponseEntity.status(INTERNAL_SERVER_ERROR).contentType(PROBLEM_JSON).body(body);
    }

    // ----- Helper -----

    private ResponseEntity<ProblemResponse> problem(DomainException e, HttpStatus status,
                                                     String title, String detail, HttpServletRequest req) {
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + e.code().toLowerCase().replace('_', '-'),
                e.code(),
                title,
                status.value(),
                detail,
                e.params(),
                req.getRequestURI(),
                MDC.get("correlationId"),
                Instant.now(),
                null);
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body);
    }
}
