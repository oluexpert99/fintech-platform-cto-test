package com.example.fintech.transactions.domain.model;

import java.util.Currency;
import java.util.Objects;

/**
 * Monetary value: an integer amount in minor units (cents/pence/öre) plus an ISO 4217 currency.
 * Use a {@code long} — never a float or {@code BigDecimal}. See spec §3.1 of data-model and
 * the ArchUnit gate "no BigDecimal in money path".
 */
public record Money(long amount, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amount < 0) {
            throw new IllegalArgumentException("Money amount must be >= 0, got: " + amount);
        }
    }

    public static Money of(long amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money of(long amount, Currency currency) {
        return new Money(amount, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.amount, other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(this.amount, other.amount), currency);
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return this.amount < other.amount;
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount + " " + currency.getCurrencyCode();
    }
}
