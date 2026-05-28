package com.example.fintech.accounts.messaging.event;

import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.StatusReason;

public record AccountStatusChangedEvent(
        String accountId,
        AccountStatus previousStatus,
        AccountStatus newStatus,
        StatusReason reason
) {
}
