package com.example.fintech.accounts.domain.model;

import java.util.Objects;

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
