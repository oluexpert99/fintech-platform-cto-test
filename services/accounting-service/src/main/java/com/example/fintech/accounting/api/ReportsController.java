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
            @RequestParam(name = "asOf", required = false) Instant asOf,
            @RequestParam(name = "currency", required = false) String currency) {
        return calculator.calculate(asOf, currency);
    }
}
