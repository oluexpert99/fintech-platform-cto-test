package com.example.fintech.accounts.messaging.event;

import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.AccountType;

public record AccountOpenedEvent(
        String accountId,
        String ownerUserId,
        String currency,
        AccountType type,
        String label,
        AccountStatus status
) {
}
