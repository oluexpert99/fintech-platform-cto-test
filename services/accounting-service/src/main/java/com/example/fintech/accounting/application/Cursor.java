package com.example.fintech.accounting.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Opaque, base64-encoded cursor; same pattern as transaction-service. */
public final class Cursor {

    private final String afterId;

    private Cursor(String afterId) { this.afterId = Objects.requireNonNull(afterId); }

    public String afterId() { return afterId; }
    public String encode() { return Base64.getUrlEncoder().withoutPadding().encodeToString(afterId.getBytes(StandardCharsets.UTF_8)); }

    public static Cursor of(String afterId) { return new Cursor(afterId); }

    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            return new Cursor(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }
}
