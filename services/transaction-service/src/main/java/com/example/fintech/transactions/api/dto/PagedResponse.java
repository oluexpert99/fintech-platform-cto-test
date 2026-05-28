package com.example.fintech.transactions.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Cursor-paginated collection envelope, per {@code api.md} §5.
 *
 * <p>Every collection endpoint in this service returns this shape — never a bare JSON array.
 * The envelope is forward-compatible: we can additively grow {@code page} with
 * {@code totalCount}, {@code pageNumber}, or link headers later without breaking clients.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedResponse<T>(List<T> data, PageMeta page) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageMeta(String nextCursor, boolean hasMore, int limit) { }

    public static <T> PagedResponse<T> of(List<T> data, String nextCursor, boolean hasMore, int limit) {
        return new PagedResponse<>(data, new PageMeta(nextCursor, hasMore, limit));
    }
}
