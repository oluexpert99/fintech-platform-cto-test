package com.example.fintech.transactions.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque, time-sortable account identifier. Display form is {@code ACC<ULID>} — "ACC" followed
 * by an uppercase Crockford base32 ULID, as emitted by account-service. Stored as a String in
 * Mongo; wrapped here for type safety on the boundary.
 */
public record AccountId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^ACC[A-Z0-9]{6,}$");

    public AccountId {
        Objects.requireNonNull(value, "AccountId value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("AccountId must match ^ACC[A-Z0-9]{6,}$, got: " + value);
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
