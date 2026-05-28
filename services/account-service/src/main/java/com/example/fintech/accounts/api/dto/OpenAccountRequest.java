package com.example.fintech.accounts.api.dto;

import com.example.fintech.accounts.domain.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OpenAccountRequest(
        @NotBlank String currency,
        @NotNull AccountType type,
        @NotBlank @Size(max = 120) String label
) {
}
