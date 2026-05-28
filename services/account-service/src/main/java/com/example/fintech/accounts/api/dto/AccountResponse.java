package com.example.fintech.accounts.api.dto;

import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.AccountType;
import com.example.fintech.accounts.domain.model.StatusReason;

import java.time.Instant;

public record AccountResponse(
        String id,
        String ownerUserId,
        String currency,
        AccountType type,
        String label,
        long balance,
        AccountStatus status,
        StatusReason statusReason,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
