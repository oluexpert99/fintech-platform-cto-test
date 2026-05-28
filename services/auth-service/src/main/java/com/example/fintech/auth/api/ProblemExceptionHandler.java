package com.example.fintech.auth.api;

import com.example.fintech.auth.api.dto.ProblemResponse;
import com.example.fintech.auth.domain.exception.AccountLockedException;
import com.example.fintech.auth.domain.exception.DomainException;
import com.example.fintech.auth.domain.exception.EmailAlreadyRegisteredException;
import com.example.fintech.auth.domain.exception.InvalidCredentialsException;
import com.example.fintech.auth.domain.exception.MfaInvalidException;
import com.example.fintech.auth.domain.exception.MfaRequiredException;
import com.example.fintech.auth.domain.exception.MissingIdempotencyKeyException;
import com.example.fintech.auth.domain.exception.RefreshTokenRevokedException;
import com.example.fintech.auth.domain.exception.StepUpRequiredException;
import com.example.fintech.auth.domain.exception.UserNotFoundException;
import com.example.fintech.auth.domain.exception.WeakPasswordException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

@RestControllerAdvice
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://api.example.com/problems/";
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<ProblemResponse> handle(MissingIdempotencyKeyException e, HttpServletRequest r) { return problem(e, BAD_REQUEST, "Idempotency-Key required", e.getMessage(), r); }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemResponse> handle(EmailAlreadyRegisteredException e, HttpServletRequest r) { return problem(e, CONFLICT, "Email already registered", e.getMessage(), r); }

    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<ProblemResponse> handle(WeakPasswordException e, HttpServletRequest r) { return problem(e, BAD_REQUEST, "Weak password", e.getMessage(), r); }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemResponse> handle(InvalidCredentialsException e, HttpServletRequest r) { return problem(e, UNAUTHORIZED, "Invalid credentials", e.getMessage(), r); }

    @ExceptionHandler(MfaRequiredException.class)
    public ResponseEntity<ProblemResponse> handle(MfaRequiredException e, HttpServletRequest r) { return problem(e, UNAUTHORIZED, "MFA required", e.getMessage(), r); }

    @ExceptionHandler(MfaInvalidException.class)
    public ResponseEntity<ProblemResponse> handle(MfaInvalidException e, HttpServletRequest r) {
        HttpStatus status = e.userAlreadyAuthenticated() ? UNPROCESSABLE_CONTENT : UNAUTHORIZED;
        return problem(e, status, "MFA invalid", e.getMessage(), r);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ProblemResponse> handle(AccountLockedException e, HttpServletRequest r) {
        ResponseEntity<ProblemResponse> body = problem(e, LOCKED, "Account locked", e.getMessage(), r);
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(body.getHeaders());
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        return new ResponseEntity<>(body.getBody(), headers, LOCKED);
    }

    @ExceptionHandler(RefreshTokenRevokedException.class)
    public ResponseEntity<ProblemResponse> handle(RefreshTokenRevokedException e, HttpServletRequest r) { return problem(e, UNAUTHORIZED, "Refresh token revoked", e.getMessage(), r); }

    @ExceptionHandler(StepUpRequiredException.class)
    public ResponseEntity<ProblemResponse> handle(StepUpRequiredException e, HttpServletRequest r) { return problem(e, UNAUTHORIZED, "Step-up required", e.getMessage(), r); }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemResponse> handle(UserNotFoundException e, HttpServletRequest r) { return problem(e, NOT_FOUND, "Resource not found", e.getMessage(), r); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemResponse> handle(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<ProblemResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ProblemResponse.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "VALIDATION_FAILED",
                        fe.getDefaultMessage(), Map.of()))
                .toList();
        ProblemResponse body = new ProblemResponse(PROBLEM_BASE + "validation-failed", "VALIDATION_FAILED", "Validation failed",
                BAD_REQUEST.value(), "One or more fields failed validation", Map.of(),
                req.getRequestURI(), MDC.get("correlationId"), Instant.now(), fieldErrors);
        return ResponseEntity.status(BAD_REQUEST).contentType(PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemResponse> handleMalformed(HttpMessageNotReadableException e, HttpServletRequest req) {
        return simpleProblem(BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request", req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemResponse> handleAuth(AuthenticationException e, HttpServletRequest req) {
        return simpleProblem(UNAUTHORIZED, "UNAUTHORIZED", "Unauthorized", req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemResponse> handleForbidden(AccessDeniedException e, HttpServletRequest req) {
        return simpleProblem(FORBIDDEN, "FORBIDDEN", "Forbidden", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> handleAny(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception", e);
        return simpleProblem(INTERNAL_SERVER_ERROR, "INTERNAL", "Internal server error", req);
    }

    private ResponseEntity<ProblemResponse> problem(DomainException e, HttpStatus status, String title, String detail, HttpServletRequest req) {
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + e.code().toLowerCase().replace('_', '-'),
                e.code(), title, status.value(), detail, e.params(),
                req.getRequestURI(), MDC.get("correlationId"), Instant.now(), null);
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body);
    }

    private ResponseEntity<ProblemResponse> simpleProblem(HttpStatus status, String code, String title, HttpServletRequest req) {
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + code.toLowerCase().replace('_', '-'),
                code, title, status.value(), title, Map.of(),
                req.getRequestURI(), MDC.get("correlationId"), Instant.now(), null);
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body);
    }
}
