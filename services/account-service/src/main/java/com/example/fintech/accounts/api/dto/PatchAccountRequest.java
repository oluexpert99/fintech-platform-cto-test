package com.example.fintech.accounts.api.dto;

import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.StatusReason;
import jakarta.validation.constraints.Size;

public record PatchAccountRequest(
        @Size(max = 120) String label,
        AccountStatus status,
        StatusReason reason,
        String approverId
) {
    public boolean isEmpty() {
        return label == null && status == null && reason == null && approverId == null;
    }
}
