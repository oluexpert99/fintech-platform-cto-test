package com.example.fintech.accounting.application;

import com.example.fintech.accounting.api.dto.TrialBalanceResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled reconciliation. Per {@code ARCHITECTURE.md} §10.1, every interval we recompute the
 * trial balance against the projection and assert {@code delta == 0}. A non-zero delta is a
 * <strong>P1 incident</strong> — it means the ledger no longer balances, which can only happen
 * if the projection has drifted from the source-of-truth journal or a write went wrong upstream.
 *
 * <p>Exposes two metrics:
 * <ul>
 *   <li>{@code accounting_reconciliation_delta} — gauge of the latest delta. Alert if non-zero.</li>
 *   <li>{@code accounting_reconciliation_runs_total} — counter, tagged by outcome.</li>
 * </ul>
 *
 * <p>To avoid the cache-hiding-a-defect problem flagged in review, this job invalidates the
 * trial-balance cache before computing — so it always sees fresh data.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final String CACHE_NAME = "trial-balance-summary";

    private final TrialBalanceCalculator calculator;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final AtomicLong currentDelta = new AtomicLong(0);

    public ReconciliationJob(TrialBalanceCalculator calculator,
                              CacheManager cacheManager,
                              MeterRegistry meterRegistry) {
        this.calculator = calculator;
        this.cacheManager = cacheManager;
        this.meterRegistry = meterRegistry;

        // Register the delta gauge against the live AtomicLong reference.
        meterRegistry.gauge("accounting.reconciliation.delta", currentDelta, AtomicLong::doubleValue);
    }

    @Scheduled(
            initialDelayString = "${reconciliation.initial-delay-seconds:60}000",
            fixedDelayString = "${reconciliation.period-seconds:3600}000")
    public void reconcile() {
        long started = System.nanoTime();
        try {
            // Invalidate the cache so we read fresh state — never reconcile against a cached snapshot.
            Objects.requireNonNull(cacheManager.getCache(CACHE_NAME),
                    "trial-balance-summary cache not configured").clear();

            TrialBalanceResponse summary = calculator.calculate(Instant.now(), "USD");
            long delta = summary.totals().delta();
            currentDelta.set(delta);

            if (delta == 0) {
                meterRegistry.counter("accounting.reconciliation.runs.total", "outcome", "balanced").increment();
                log.info("reconciliation balanced: debits={} credits={} delta=0",
                        summary.totals().debits(), summary.totals().credits());
            } else {
                meterRegistry.counter("accounting.reconciliation.runs.total", "outcome", "drift").increment();
                log.error("RECONCILIATION DRIFT — P1 INCIDENT: debits={} credits={} delta={}",
                        summary.totals().debits(), summary.totals().credits(), delta);
                // The gauge being non-zero is what the alerting system watches.
            }
        } catch (Exception e) {
            meterRegistry.counter("accounting.reconciliation.runs.total", "outcome", "error").increment();
            log.error("reconciliation job failed", e);
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            log.debug("reconciliation run completed in {}ms", elapsedMs);
        }
    }
}
