package com.example.fintech.accounting.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartOfAccountsResponse(
        String id,
        String name,
        String type,
        String normalSide,
        String parentId,
        boolean system,
        String currency,
        Instant createdAt
) { }
