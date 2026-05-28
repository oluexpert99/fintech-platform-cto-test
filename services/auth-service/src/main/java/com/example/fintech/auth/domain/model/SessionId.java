package com.example.fintech.auth.domain.model;

import com.github.f4b6a3.ulid.UlidCreator;

import java.util.Objects;

public record SessionId(String value) {
    public SessionId {
        Objects.requireNonNull(value, "SessionId value");
        if (value.isBlank()) throw new IllegalArgumentException("SessionId must not be blank");
    }

    public static SessionId generate() { return new SessionId("SES-" + UlidCreator.getUlid().toString()); }
    public static SessionId of(String value) { return new SessionId(value); }

    @Override public String toString() { return value; }
}
