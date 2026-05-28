package com.example.fintech.accounting.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrialBalanceResponse(
        Instant asOf,
        String currency,
        Totals totals,
        Map<String, TypeRollup> byType
) {
    public record Totals(long debits, long credits, long delta) { }

    public record TypeRollup(long debits, long credits, long net) { }
}
