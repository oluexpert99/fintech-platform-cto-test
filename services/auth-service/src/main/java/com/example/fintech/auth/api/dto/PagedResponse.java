package com.example.fintech.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedResponse<T>(List<T> data, PageMeta page) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageMeta(String nextCursor, boolean hasMore, int limit) { }

    public static <T> PagedResponse<T> of(List<T> data, String nextCursor, boolean hasMore, int limit) {
        return new PagedResponse<>(data, new PageMeta(nextCursor, hasMore, limit));
    }
}
