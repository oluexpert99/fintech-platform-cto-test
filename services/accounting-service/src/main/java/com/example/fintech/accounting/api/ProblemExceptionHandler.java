package com.example.fintech.accounting.api;

import com.example.fintech.accounting.api.dto.ProblemResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

import static org.springframework.http.HttpStatus.*;

@RestControllerAdvice
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://api.example.com/problems/";
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemResponse> auth(AuthenticationException e, HttpServletRequest r) {
        return problem(UNAUTHORIZED, "UNAUTHORIZED", "Unauthorized", "Authentication required", r);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemResponse> forbidden(AccessDeniedException e, HttpServletRequest r) {
        return problem(FORBIDDEN, "FORBIDDEN", "Forbidden", "Caller lacks required role", r);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemResponse> badInput(IllegalArgumentException e, HttpServletRequest r) {
        return problem(BAD_REQUEST, "MALFORMED_REQUEST", "Bad request", e.getMessage(), r);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> any(Exception e, HttpServletRequest r) {
        log.error("Unhandled exception", e);
        return problem(INTERNAL_SERVER_ERROR, "INTERNAL", "Internal server error", "An unexpected error occurred", r);
    }

    private ResponseEntity<ProblemResponse> problem(HttpStatus status, String code, String title, String detail, HttpServletRequest r) {
        ProblemResponse body = new ProblemResponse(
                PROBLEM_BASE + code.toLowerCase().replace('_', '-'),
                code, title, status.value(), detail, Map.of(),
                r.getRequestURI(), MDC.get("correlationId"), Instant.now());
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body);
    }
}
