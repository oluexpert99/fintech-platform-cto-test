package com.example.fintech.accounts.domain.model;

import com.github.f4b6a3.ulid.UlidCreator;

import java.util.Objects;
import java.util.regex.Pattern;

public record AccountId(String value) {
    private static final Pattern PATTERN = Pattern.compile("^ACC[A-Z0-9]{8,}$");

    public AccountId {
        Objects.requireNonNull(value, "AccountId value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("AccountId format is invalid: " + value);
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    public static AccountId generate() {
        return new AccountId("ACC" + UlidCreator.getMonotonicUlid().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
