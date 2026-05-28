package com.example.fintech.transactions.domain.model;

import com.github.f4b6a3.ulid.UlidCreator;

import java.util.Objects;
import java.util.regex.Pattern;

public record JournalEntryId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^JL-[0-9A-HJKMNP-TV-Z]{26}$");

    public JournalEntryId {
        Objects.requireNonNull(value, "JournalEntryId value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("JournalEntryId must match JL-<ulid>, got: " + value);
        }
    }

    public static JournalEntryId generate() {
        return new JournalEntryId("JL-" + UlidCreator.getUlid().toString());
    }

    public static JournalEntryId of(String value) {
        return new JournalEntryId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
