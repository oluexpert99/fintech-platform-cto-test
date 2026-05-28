package com.example.fintech.transactions.api.dto;

import com.example.fintech.transactions.domain.model.TransactionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /v1/transactions}.
 *
 * <p>The {@code type} discriminator distinguishes a normal user transfer from an operator
 * reversal. See {@code api.md} §10 and {@code transaction-service.spec} §3.1.
 *
 * <p>Note: every constraint here is asserted by the Jakarta Bean Validation runtime;
 * violations produce {@code 400 VALIDATION_FAILED} with field-level codes via
 * {@code ProblemExceptionHandler}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateTransactionRequest(

        @NotNull
        TransactionType type,

        // ----- TRANSFER fields -----
        @Pattern(regexp = "^ACC[0-9]{6,}$", message = "INVALID_FORMAT")
        String sourceAccount,

        @Pattern(regexp = "^ACC[0-9]{6,}$", message = "INVALID_FORMAT")
        String destinationAccount,

        @Positive(message = "MUST_BE_POSITIVE")
        Long amount,

        @Pattern(regexp = "^[A-Z]{3}$", message = "INVALID_FORMAT")
        String currency,

        @Size(max = 140, message = "TOO_LONG")
        String description,

        // ----- REVERSAL fields -----
        @Pattern(regexp = "^TX-[0-9A-HJKMNP-TV-Z]{26}$", message = "INVALID_FORMAT")
        String correctsTransactionId,

        @Size(max = 500, message = "TOO_LONG")
        String reason,

        String approverId
) {
    /**
     * Discriminator-driven field requirements live in the service layer (not here) so we can
     * emit a typed {@code DomainException} rather than a generic Bean Validation error.
     */
}
