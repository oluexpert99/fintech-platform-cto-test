package com.example.fintech.transactions.domain.exception;

import java.util.Map;

public final class CurrencyMismatchException extends DomainException {

    private final String requestedCurrency;
    private final String sourceCurrency;
    private final String destinationCurrency;

    public CurrencyMismatchException(String requestedCurrency, String sourceCurrency, String destinationCurrency) {
        super("Currency mismatch: request=" + requestedCurrency
                + " source=" + sourceCurrency + " destination=" + destinationCurrency);
        this.requestedCurrency = requestedCurrency;
        this.sourceCurrency = sourceCurrency;
        this.destinationCurrency = destinationCurrency;
    }

    public String requestedCurrency() {
        return requestedCurrency;
    }

    public String sourceCurrency() {
        return sourceCurrency;
    }

    public String destinationCurrency() {
        return destinationCurrency;
    }

    @Override
    public String code() {
        return "CURRENCY_MISMATCH";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of(
                "requestedCurrency", requestedCurrency,
                "sourceCurrency", sourceCurrency,
                "destinationCurrency", destinationCurrency);
    }
}
