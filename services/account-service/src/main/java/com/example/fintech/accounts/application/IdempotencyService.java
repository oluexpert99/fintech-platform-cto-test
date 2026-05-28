package com.example.fintech.accounts.application;

import com.example.fintech.accounts.persistence.document.AccountDocument;
import com.example.fintech.accounts.persistence.repository.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class IdempotencyService {
    private static final ObjectMapper CANONICAL = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final AccountRepository accountRepository;

    public IdempotencyService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public String scopedKey(String caller, String operation, String clientKey) {
        return sha256Hex(caller + "|" + operation + "|" + clientKey);
    }

    public String payloadHash(Object request) {
        try {
            return sha256Hex(CANONICAL.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash payload", e);
        }
    }

    public Optional<AccountDocument> findExisting(String scopedKey) {
        return accountRepository.findByIdempotencyKey(scopedKey);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
