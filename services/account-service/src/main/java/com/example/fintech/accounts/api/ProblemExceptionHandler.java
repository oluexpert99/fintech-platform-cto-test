package com.example.fintech.accounts.api;

import com.example.fintech.accounts.api.dto.ProblemResponse;
import com.example.fintech.accounts.domain.exception.DomainException;
import com.example.fintech.accounts.domain.exception.IdempotencyInProgressException;
import jakarta.servlet.http.HttpServletRequest;
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

@RestControllerAdvice
public class ProblemExceptionHandler {
    private static final String PROBLEM_BASE = "https://api.example.com/problems/";
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemResponse> handleDomain(DomainException e, HttpServletRequest req) {
        HttpStatus status = switch (e.code()) {
            case "ACCOUNT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "MISSING_IDEMPOTENCY_KEY", "MALFORMED_REQUEST", "VALIDATION_FAILED" -> HttpStatus.BAD_REQUEST;
            case "IDEMPOTENCY_KEY_CONFLICT", "IDEMPOTENCY_IN_PROGRESS", "VERSION_CONFLICT", "INVALID_STATE_TRANSITION" -> HttpStatus.CONFLICT;
            case "OPERATOR_APPROVAL_REQUIRED", "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        var response = problem(e.code(), status, e.getMessage(), e.params(), req.getRequestURI());
        if (e instanceof IdempotencyInProgressException inProgress) {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(response.getHeaders());
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(inProgress.retryAfterSeconds()));
            return new ResponseEntity<>(response.getBody(), headers, status);
        }
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<ProblemResponse.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ProblemResponse.FieldError(fe.getField(), "VALIDATION_FAILED", fe.getDefaultMessage(), Map.of()))
                .toList();
        ProblemResponse body = problemBody("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, "Validation failed", Map.of(), req.getRequestURI(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemResponse> handleMalformed(HttpMessageNotReadableException e, HttpServletRequest req) {
        return problem("MALFORMED_REQUEST", HttpStatus.BAD_REQUEST, "Request body could not be parsed", Map.of(), req.getRequestURI());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemResponse> handleAuth(AuthenticationException e, HttpServletRequest req) {
        return problem("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Authentication required", Map.of(), req.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemResponse> handleForbidden(AccessDeniedException e, HttpServletRequest req) {
        return problem("FORBIDDEN", HttpStatus.FORBIDDEN, "Caller lacks required permission", Map.of(), req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> handleAny(Exception e, HttpServletRequest req) {
        return problem("INTERNAL", HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", Map.of(), req.getRequestURI());
    }

    private ResponseEntity<ProblemResponse> problem(String code, HttpStatus status, String detail, Map<String, Object> params, String instance) {
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(problemBody(code, status, detail, params, instance, null));
    }

    private ProblemResponse problemBody(String code, HttpStatus status, String detail, Map<String, Object> params, String instance, List<ProblemResponse.FieldError> errors) {
        return new ProblemResponse(
                PROBLEM_BASE + code.toLowerCase().replace('_', '-'),
                code,
                status.getReasonPhrase(),
                status.value(),
                detail,
                params,
                instance,
                MDC.get("correlationId"),
                Instant.now(),
                errors
        );
    }
}
