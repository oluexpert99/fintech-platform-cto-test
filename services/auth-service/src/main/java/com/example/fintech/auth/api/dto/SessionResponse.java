package com.example.fintech.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResponse(
        String sessionId,
        String userId,
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        long refreshExpiresIn,
        String scope,
        String deviceLabel,
        Boolean current,
        Instant createdAt,
        Instant lastSeenAt
) { }
