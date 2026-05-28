package com.example.fintech.transactions.application;

import com.example.fintech.transactions.api.dto.CreateTransactionRequest;
import com.example.fintech.transactions.domain.model.UserId;
import com.example.fintech.transactions.persistence.document.TransactionDocument;
import com.example.fintech.transactions.persistence.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.json.JsonMapper.Builder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Idempotency-key scoping and lookup.
 *
 * <p>The race-free guarantee comes from the unique index on {@code transactions.idempotencyKey} —
 * NOT from the {@link #findExisting} method below. {@code findExisting} is for the fast-path replay;
 * the unique index is the arbiter when two concurrent requests carry the same key.
 *
 * <p>See {@code transaction-service.spec} §4.3 and ADR-0002.
 */
@Service
public class IdempotencyService {

    /** Canonical-JSON mapper: sorted map keys, deterministic property order. */
    private static final JsonMapper CANONICAL = canonicalMapper();

    private static JsonMapper canonicalMapper() {
        Builder b = JsonMapper.builder();
        b.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        b.configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return b.build();
    }

    private final TransactionRepository transactionRepository;

    public IdempotencyService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Construct the scoped key: {@code sha256(userId + "|" + operation + "|" + clientKey)}.
     */
    public String scopedKey(UserId caller, String operation, String clientKey) {
        return sha256Hex(caller.value() + "|" + operation + "|" + clientKey);
    }

    /**
     * Look up an existing transaction by scoped key, if any.
     */
    public Optional<TransactionDocument> findExisting(String scopedKey) {
        return transactionRepository.findByIdempotencyKey(scopedKey);
    }

    /**
     * SHA-256 of the canonical JSON of the request payload. Used for replay matching.
     * Canonical = map keys sorted; ISO-8601 dates as strings.
     */
    public String payloadHash(CreateTransactionRequest request) {
        // Jackson 3 exceptions are unchecked; no try/catch needed for shape errors here.
        String canonical = CANONICAL.writeValueAsString(request);
        return sha256Hex(canonical);
    }

    /**
     * Compare a stored transaction's payload hash to a new incoming one.
     */
    public boolean payloadsMatch(TransactionDocument stored, String newPayloadHash) {
        return stored.getPayloadHash() != null && stored.getPayloadHash().equals(newPayloadHash);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
