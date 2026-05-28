package com.example.fintech.transactions.domain.model;

import com.github.f4b6a3.ulid.UlidCreator;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque, ULID-based transaction identifier. Format: {@code TX-<26-char Crockford base32>}.
 * Time-sortable, which keeps the {@code transactions} indexes cache-friendly.
 */
public record TransactionId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^TX-[0-9A-HJKMNP-TV-Z]{26}$");

    public TransactionId {
        Objects.requireNonNull(value, "TransactionId value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("TransactionId must match TX-<ulid>, got: " + value);
        }
    }

    public static TransactionId generate() {
        return new TransactionId("TX-" + UlidCreator.getUlid().toString());
    }

    public static TransactionId of(String value) {
        return new TransactionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
