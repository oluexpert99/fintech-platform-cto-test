package com.example.fintech.transactions.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque, time-sortable account identifier. Display form is {@code ACC<6+ digits>}.
 * Stored as a String in Mongo; wrapped here for type safety on the boundary.
 */
public record AccountId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^ACC[0-9]{6,}$");

    public AccountId {
        Objects.requireNonNull(value, "AccountId value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("AccountId must match ACC<6+ digits>, got: " + value);
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
