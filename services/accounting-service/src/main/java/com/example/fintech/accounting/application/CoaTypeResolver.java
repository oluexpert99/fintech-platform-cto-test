package com.example.fintech.accounting.application;

import com.example.fintech.accounting.persistence.document.ChartOfAccountsDocument;
import com.example.fintech.accounting.persistence.repository.ChartOfAccountsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves a {@code coaAccount} value (e.g. {@code "1000"}, {@code "2100.ACC100001"}) to its
 * top-level COA type ({@code ASSET}, {@code LIABILITY}, …).
 *
 * <p>Resolution rules in order:
 * <ol>
 *   <li>Take the prefix up to the first dot (so {@code "2100.ACC..."} → {@code "2100"}).</li>
 *   <li>Look up the prefix in {@code chart_of_accounts}.</li>
 *   <li>If not found, fall back to first-digit class lookup, log a warning and increment a
 *       counter — unrecognised account codes should be loud, not silent.</li>
 * </ol>
 *
 * <p>Cache is a {@link ConcurrentHashMap}, safe under concurrent access. New COA rows added at
 * runtime are picked up after the cache is cleared via {@link #invalidate()} (called by the
 * reconciliation job before every run) or on application restart.
 */
@Component
public class CoaTypeResolver {

    private static final Logger log = LoggerFactory.getLogger(CoaTypeResolver.class);

    public enum CoaType { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE, UNKNOWN }

    private final ChartOfAccountsRepository coaRepo;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, CoaType> cache = new ConcurrentHashMap<>();

    public CoaTypeResolver(ChartOfAccountsRepository coaRepo, MeterRegistry meterRegistry) {
        this.coaRepo = coaRepo;
        this.meterRegistry = meterRegistry;
    }

    public CoaType typeOf(String coaAccount) {
        if (coaAccount == null || coaAccount.isBlank()) {
            return CoaType.UNKNOWN;
        }
        String prefix = coaAccount.contains(".") ? coaAccount.substring(0, coaAccount.indexOf('.')) : coaAccount;
        return cache.computeIfAbsent(prefix, this::lookup);
    }

    /** Clears the lookup cache. Useful when new COA rows are seeded after boot. */
    public void invalidate() {
        cache.clear();
    }

    private CoaType lookup(String prefix) {
        return coaRepo.findById(prefix)
                .map(ChartOfAccountsDocument::getType)
                .map(CoaTypeResolver::parse)
                .orElseGet(() -> {
                    CoaType fallback = classByLeadingDigit(prefix);
                    log.warn("Unrecognised COA prefix '{}' — falling back to leading-digit class {}. "
                            + "Likely cause: journal entry references a coaAccount that wasn't seeded.",
                            prefix, fallback);
                    meterRegistry.counter("accounting.coa.unknown.total", "prefix", prefix).increment();
                    return fallback;
                });
    }

    private static CoaType parse(String type) {
        try { return CoaType.valueOf(type); } catch (IllegalArgumentException e) { return CoaType.UNKNOWN; }
    }

    /** Fallback class assignment by leading digit, per standard 5-class numbering. */
    private static CoaType classByLeadingDigit(String prefix) {
        if (prefix.isEmpty()) return CoaType.UNKNOWN;
        return switch (prefix.charAt(0)) {
            case '1' -> CoaType.ASSET;
            case '2' -> CoaType.LIABILITY;
            case '3' -> CoaType.EQUITY;
            case '4' -> CoaType.REVENUE;
            case '5' -> CoaType.EXPENSE;
            default  -> CoaType.UNKNOWN;
        };
    }
}
