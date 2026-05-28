package com.example.fintech.transactions.domain.model;

import java.util.Objects;

/**
 * The authenticated caller's identity, drawn from the JWT {@code sub} claim.
 * Format mirrors {@code users._id}: {@code U-<ulid>}.
 */
public record UserId(String value) {

    public UserId {
        Objects.requireNonNull(value, "UserId value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("UserId value must not be blank");
        }
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
