package com.example.fintech.transactions.domain.policy;

import com.example.fintech.transactions.domain.exception.CurrencyMismatchException;
import org.springframework.stereotype.Component;

/**
 * Single-currency-per-transfer enforcement.
 *
 * <p>Per the scope decision (full-Keycloak / polling-outbox / single-currency / 4-real-services),
 * source, destination, and request currency must all match. There is no FX in this submission;
 * cross-currency requests return {@code 422 CURRENCY_MISMATCH}.
 */
@Component
public class CurrencyPolicy {

    public void requireSameCurrency(String requested, String sourceCurrency, String destinationCurrency) {
        if (!requested.equals(sourceCurrency) || !requested.equals(destinationCurrency)) {
            throw new CurrencyMismatchException(requested, sourceCurrency, destinationCurrency);
        }
    }
}
