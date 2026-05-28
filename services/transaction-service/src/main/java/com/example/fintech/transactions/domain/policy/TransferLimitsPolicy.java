package com.example.fintech.transactions.domain.policy;

import com.example.fintech.transactions.domain.exception.LimitExceededException;
import com.example.fintech.transactions.domain.model.UserId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Enforces per-transaction and per-day amount limits.
 *
 * <p>See {@code transaction-service.spec} §4.1 step "transferLimitsPolicy.allow(...)" and
 * §4.5 for the configurable knobs.
 *
 * <p>Per-day aggregation needs a repository query over recent transactions for the user; for the
 * scaffold only the per-tx ceiling is enforced. Step-up logic was removed per ADR-0006 (MFA
 * out of scope for the test deliverable).
 */
@Component
public class TransferLimitsPolicy {

    private final long perTxMaxAmount;
    @SuppressWarnings("unused")  // wired but unused until per-day aggregation lands
    private final long perDayMaxAmount;

    public TransferLimitsPolicy(
            @Value("${transactions.limits.per-tx-max-amount:100000000}") long perTxMaxAmount,
            @Value("${transactions.limits.per-day-max-amount:1000000000}") long perDayMaxAmount) {
        this.perTxMaxAmount = perTxMaxAmount;
        this.perDayMaxAmount = perDayMaxAmount;
    }

    public void check(UserId caller, long amount, String currency) {
        if (amount > perTxMaxAmount) {
            throw new LimitExceededException("PER_TX", amount, perTxMaxAmount, currency);
        }
        // TODO(spec §4.1): query recent transactions for `caller` in the last 24h and
        //                  reject if (sum + amount) > perDayMaxAmount.
    }
}
