package com.example.fintech.accounting.application;

import com.example.fintech.accounting.api.dto.TrialBalanceResponse;
import com.example.fintech.accounting.persistence.document.JournalEntryDocument;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;

/**
 * Trial balance summary. Two roll-ups:
 * <ul>
 *   <li>System-wide totals: Σdebits, Σcredits, delta. {@code delta} must be 0.</li>
 *   <li>Per-COA-type roll-up: ASSET / LIABILITY / EQUITY / REVENUE / EXPENSE.</li>
 * </ul>
 *
 * <p>Aggregations run on Mongo (server-side) — we never pull the journal into the JVM. Results
 * are cacheable on {@code (asOf, currency)}; TTL configured in application.yaml.
 */
@Service
public class TrialBalanceCalculator {

    private final MongoTemplate mongoTemplate;
    private final CoaTypeResolver typeResolver;
    /**
     * Resolved at construction time from the document mapping so we can never drift from the
     * actual collection name. Previously hard-coded to {@code "journal"} which referred to a
     * collection that didn't exist in {@code fintech_accounting} after the projection split —
     * making the trial-balance always return zeros and the reconciliation gauge always read 0.
     */
    private final String journalCollection;

    public TrialBalanceCalculator(MongoTemplate mongoTemplate, CoaTypeResolver typeResolver) {
        this.mongoTemplate = mongoTemplate;
        this.typeResolver = typeResolver;
        this.journalCollection = mongoTemplate.getCollectionName(
                com.example.fintech.accounting.persistence.document.JournalEntryDocument.class);
    }

    @Cacheable(value = "trial-balance-summary", key = "T(java.util.Objects).hash(#asOf, #currency)")
    public TrialBalanceResponse calculate(Instant asOf, String currency) {
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        String effectiveCurrency = currency != null ? currency : "USD";

        Criteria criteria = Criteria.where("postedAt").lte(effectiveAsOf)
                .and("currency").is(effectiveCurrency);

        // Aggregate by (coaAccount, account, side) → sum(amount).
        // `account` is grouped too so we can derive a fallback COA ref for legacy entries
        // that pre-date the coaAccount field (their value will be null).
        Aggregation agg = newAggregation(
                match(criteria),
                group("coaAccount", "account", "side").sum("amount").as("total")
        );

        AggregationResults<Map> results = mongoTemplate.aggregate(agg, journalCollection, Map.class);

        long totalDebits = 0;
        long totalCredits = 0;
        Map<CoaTypeResolver.CoaType, long[]> byType = new EnumMap<>(CoaTypeResolver.CoaType.class);

        for (Map<?, ?> row : results.getMappedResults()) {
            Map<?, ?> groupKey = (Map<?, ?>) row.get("_id");
            String coaAccount = groupKey == null ? null : asString(groupKey.get("coaAccount"));
            // Fallback for legacy rows missing coaAccount: derive from `account` via the resolver default
            String account = groupKey == null ? null : asString(groupKey.get("account"));
            String coaRef = coaAccount != null ? coaAccount : (account != null ? "2100." + account : null);

            String side = groupKey == null ? null : asString(groupKey.get("side"));
            long total = asLong(row.get("total"));

            CoaTypeResolver.CoaType type = typeResolver.typeOf(coaRef);
            long[] bucket = byType.computeIfAbsent(type, t -> new long[]{0L, 0L}); // [debits, credits]

            if ("DEBIT".equals(side)) {
                totalDebits += total;
                bucket[0] += total;
            } else if ("CREDIT".equals(side)) {
                totalCredits += total;
                bucket[1] += total;
            }
        }

        Map<String, TrialBalanceResponse.TypeRollup> byTypeOut = new TreeMap<>();
        for (var e : byType.entrySet()) {
            long debits = e.getValue()[0];
            long credits = e.getValue()[1];
            byTypeOut.put(e.getKey().name(), new TrialBalanceResponse.TypeRollup(debits, credits, debits - credits));
        }

        TrialBalanceResponse.Totals totals = new TrialBalanceResponse.Totals(
                totalDebits, totalCredits, totalDebits - totalCredits);

        return new TrialBalanceResponse(effectiveAsOf, effectiveCurrency, totals, byTypeOut);
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    private static long asLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
