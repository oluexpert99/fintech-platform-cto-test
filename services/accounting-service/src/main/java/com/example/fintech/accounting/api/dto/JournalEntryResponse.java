package com.example.fintech.accounting.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JournalEntryResponse(
        String journalEntryId,
        String transactionId,
        String account,
        String coaAccount,
        String side,
        long amount,
        String currency,
        Instant postedAt
) { }
