package com.example.fintech.auth.api.dto;

import com.example.fintech.auth.domain.model.KycLevel;
import com.example.fintech.auth.domain.model.UserStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(
        String userId,
        String email,
        String fullName,
        String phone,
        UserStatus status,
        KycLevel kycLevel,
        Boolean mfaEnabled,
        Instant createdAt,
        Instant updatedAt,
        Long version
) { }
