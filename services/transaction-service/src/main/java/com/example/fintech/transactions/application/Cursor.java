package com.example.fintech.transactions.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Opaque, base64-encoded cursor. Clients round-trip the value verbatim; never parse it.
 *
 * <p>For this service the cursor wraps the last document's {@code _id} (a ULID, which is
 * time-sortable). Listing is keyset-paginated on {@code _id} in descending order — newer first.
 * Concurrent inserts at the top don't shift the view because the cursor pins us to "items with
 * _id &lt; this".
 *
 * <p>If we later need a multi-field sort, the cursor format evolves additively (e.g. base64 of
 * a small JSON {@code {"id":"...", "createdAt":"..."}}). Clients won't notice — the cursor is
 * opaque to them.
 */
public final class Cursor {

    private final String afterId;

    private Cursor(String afterId) {
        this.afterId = Objects.requireNonNull(afterId);
    }

    public String afterId() {
        return afterId;
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(afterId.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor of(String afterId) {
        return new Cursor(afterId);
    }

    /**
     * Parse an opaque cursor string. Returns {@code null} for null or blank input.
     * Invalid cursors throw {@link IllegalArgumentException} — caller should map to 400.
     */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new Cursor(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }
}
