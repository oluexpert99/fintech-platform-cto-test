package com.example.fintech.accounts.api.dto;

import java.time.Instant;

public record BalanceResponse(
        String accountId,
        long balance,
        String currency,
        Instant updatedAt
) {
}
