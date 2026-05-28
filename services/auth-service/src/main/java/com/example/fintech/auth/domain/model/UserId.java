package com.example.fintech.auth.domain.model;

import com.github.f4b6a3.ulid.UlidCreator;

import java.util.Objects;

public record UserId(String value) {
    public UserId {
        Objects.requireNonNull(value, "UserId value");
        if (value.isBlank()) throw new IllegalArgumentException("UserId must not be blank");
    }

    public static UserId generate() { return new UserId("U-" + UlidCreator.getUlid().toString()); }
    public static UserId of(String value) { return new UserId(value); }

    @Override public String toString() { return value; }
}
