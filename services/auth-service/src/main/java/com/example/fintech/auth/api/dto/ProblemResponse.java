package com.example.fintech.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemResponse(
        String type,
        String code,
        String title,
        int status,
        String detail,
        Map<String, Object> params,
        String instance,
        String correlationId,
        Instant timestamp,
        List<FieldError> errors
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String code, String message, Map<String, Object> params) { }
}
