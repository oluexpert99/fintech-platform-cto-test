package com.example.fintech.accounting.api;

import com.example.fintech.accounting.api.dto.TrialBalanceResponse;
import com.example.fintech.accounting.application.TrialBalanceCalculator;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping(path = "/v1/reports", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReportsController {

    private final TrialBalanceCalculator calculator;

    public ReportsController(TrialBalanceCalculator calculator) {
        this.calculator = calculator;
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAnyAuthority('SCOPE_admin:*', 'ROLE_auditor')")
    public TrialBalanceResponse trialBalance(
            @RequestParam(name = "asOf", required = false) String asOf,
            @RequestParam(name = "currency", required = false) String currency) {
        return calculator.calculate(parseAsOf(asOf), currency);
    }

    /**
     * Accepts an ISO instant ({@code 2026-05-29T00:00:00Z}), an ISO local date-time without zone
     * ({@code 2026-05-29T00:00}, as emitted by the UI's datetime-local input — assumed UTC), or a
     * plain date ({@code 2026-05-29}). Bound as a String (not Instant) so a bad value yields a
     * clean 400 via the IllegalArgumentException handler rather than a 500 type-mismatch.
     * Null/blank means "now" (resolved by the calculator).
     */
    private static Instant parseAsOf(String asOf) {
        if (asOf == null || asOf.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(asOf);
        } catch (DateTimeParseException ignored) {
            // try next format
        }
        try {
            return LocalDateTime.parse(asOf).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // try next format
        }
        try {
            return LocalDate.parse(asOf).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException(
                    "asOf must be ISO-8601: 2026-05-29T00:00:00Z, 2026-05-29T00:00, or 2026-05-29");
        }
    }
}
