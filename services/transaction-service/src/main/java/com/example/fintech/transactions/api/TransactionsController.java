package com.example.fintech.transactions.api;

import com.example.fintech.transactions.api.dto.CreateTransactionRequest;
import com.example.fintech.transactions.api.dto.PagedResponse;
import com.example.fintech.transactions.api.dto.TransactionResponse;
import com.example.fintech.transactions.application.TransactionFinder;
import com.example.fintech.transactions.application.TransactionWriteService;
import com.example.fintech.transactions.domain.exception.IdempotencyInProgressException;
import com.example.fintech.transactions.domain.exception.MissingIdempotencyKeyException;
import com.example.fintech.transactions.domain.model.TransactionId;
import com.example.fintech.transactions.domain.model.UserId;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping(path = "/v1/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransactionsController {

    private final TransactionWriteService writeService;
    private final TransactionFinder finder;

    public TransactionsController(TransactionWriteService writeService, TransactionFinder finder) {
        this.writeService = writeService;
        this.finder = finder;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    // Money movement requires `transactions:write` (TRANSFER) or `transactions:reverse` (REVERSAL).
    // Either scope is sufficient at this layer; the service-layer dispatch then enforces the
    // type-specific authorization (operator role + dual control for REVERSAL).
    @PreAuthorize("hasAnyAuthority('SCOPE_transactions:write', 'SCOPE_transactions:reverse')")
    public ResponseEntity<TransactionResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        UserId caller = UserId.of(jwt.getSubject());

        TransactionResponse response;
        try {
            response = writeService.create(caller, rolesOf(jwt), idempotencyKey, request);
        } catch (RuntimeException e) {
            // Two concurrent requests with the same key raced past the idempotency lookup;
            // the second one to call insert() lost on the unique index. The DuplicateKeyException
            // can be wrapped in a Spring TransactionSystemException / UncategorizedMongoDbException
            // because we're inside a @Transactional executor — unwrap the cause chain to detect it.
            if (isDuplicateKey(e)) {
                throw new IdempotencyInProgressException(2L);
            }
            throw e;
        }

        URI location = response != null && response.transactionId() != null
                ? URI.create("/v1/transactions/" + response.transactionId())
                : null;
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{transactionId}")
    @PreAuthorize("hasAuthority('SCOPE_transactions:read')")
    public TransactionResponse get(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable String transactionId) {
        return finder.get(UserId.of(jwt.getSubject()), TransactionId.of(transactionId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_transactions:read')")
    public PagedResponse<TransactionResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return finder.list(UserId.of(jwt.getSubject()), cursor, limit);
    }

    private static Set<String> rolesOf(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            return roles.stream().map(Object::toString).collect(Collectors.toSet());
        }
        return Set.of();
    }

    /**
     * Walks the cause chain to detect a {@link DuplicateKeyException} that may have been wrapped
     * by Spring's transaction infrastructure (e.g. {@code TransactionSystemException} or
     * {@code UncategorizedMongoDbException}). Also matches by message for defence in depth — Mongo
     * driver messages reliably contain "E11000" on duplicate-key violations.
     */
    private static boolean isDuplicateKey(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) return true;
            String msg = cause.getMessage();
            if (msg != null && msg.contains("E11000")) return true;
            if (cause == cause.getCause()) break;  // defensive: prevent infinite loop
        }
        return false;
    }
}
