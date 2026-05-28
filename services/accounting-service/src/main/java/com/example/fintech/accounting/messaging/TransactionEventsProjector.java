package com.example.fintech.accounting.messaging;

import com.example.fintech.accounting.persistence.document.JournalEntryDocument;
import com.example.fintech.accounting.persistence.document.ProcessedEventDocument;
import com.example.fintech.accounting.persistence.repository.JournalEntryRepository;
import com.example.fintech.accounting.persistence.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real Kafka consumer that materialises {@code transactions.transfer.completed} and
 * {@code transactions.transfer.reversed} events into accounting-service's own
 * {@code journal_projection} collection (in the {@code fintech_accounting} database).
 *
 * <p>Replaces the previous no-op listener that left accounting-service reading transaction-service's
 * private {@code journal} collection directly. With this projector in place: accounting has its
 * own database, no shared-collection coupling, reads don't compete with the write path, and the
 * projection can be rebuilt from offset 0 of an infinite-retention topic.
 *
 * <h3>Pipeline (per events.spec §6.3, with the second-review reviewer's correction applied)</h3>
 * <ol>
 *   <li><strong>Validate</strong> envelope shape. On failure → {@link MalformedEventException},
 *       which the {@code DefaultErrorHandler} routes to {@code <topic>.DLT}. We never claim an
 *       inbox row for a malformed event — preventing the silent-data-loss path where a bad event
 *       would be dedupe-skipped on retry.</li>
 *   <li><strong>Project</strong> via {@code saveAll} of journal lines with deterministic
 *       {@code _id} values ({@code JL-PROJ-<txId>-DR|CR}). Re-running this is idempotent at the
 *       Mongo level — duplicate event delivery re-projects the same rows, which is a no-op.</li>
 *   <li><strong>Inbox insert</strong> for observability: a duplicate-key indicates this delivery
 *       was a redelivery; we record it via {@code events.deduped.total}. The inbox is no longer
 *       on the side-effect critical path — projection idempotency is.</li>
 *   <li><strong>Ack</strong> the offset only after projection succeeds.</li>
 * </ol>
 */
@Component
public class TransactionEventsProjector {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventsProjector.class);
    private static final String CONSUMER_GROUP = "accounting";

    private final ProcessedEventRepository inboxRepo;
    private final JournalEntryRepository journalRepo;
    private final MeterRegistry meterRegistry;
    private final Timer projectTimer;
    private final long inboxRetentionDays;

    public TransactionEventsProjector(ProcessedEventRepository inboxRepo,
                                       JournalEntryRepository journalRepo,
                                       MeterRegistry meterRegistry,
                                       @Value("${accounting.inbox.retention-days:30}") long inboxRetentionDays) {
        this.inboxRepo = inboxRepo;
        this.journalRepo = journalRepo;
        this.meterRegistry = meterRegistry;
        this.inboxRetentionDays = inboxRetentionDays;
        this.projectTimer = Timer.builder("accounting.projection.duration")
                .description("Time to materialise one event into the projection")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = {"transactions.transfer.completed", "transactions.transfer.reversed"},
            groupId = "${spring.kafka.consumer.group-id:accounting}",
            properties = {"spring.json.value.default.type=java.util.Map"})
    public void onEvent(Map<String, Object> envelope, Acknowledgment ack) {
        // Null envelope is a malformed message — route to DLT, don't ack silently.
        if (envelope == null) {
            throw new MalformedEventException("Kafka record value was null");
        }

        // 1. Validate up-front. Failures throw → DefaultErrorHandler → DLT. No inbox claim.
        ParsedEvent parsed = validate(envelope);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Instant now = Instant.now();

            // 2. Project (idempotent on stable _id).
            journalRepo.saveAll(rowsFor(parsed, now));

            // 3. Inbox insert — observability only. Duplicate = redelivery; not an error.
            try {
                inboxRepo.insert(new ProcessedEventDocument(
                        parsed.eventId, parsed.topic, parsed.eventType, CONSUMER_GROUP, now,
                        now.plus(inboxRetentionDays, ChronoUnit.DAYS)));
                meterRegistry.counter("events.consumed.total",
                        "consumer_group", CONSUMER_GROUP, "event_type", parsed.eventType).increment();
            } catch (DuplicateKeyException dup) {
                meterRegistry.counter("events.deduped.total",
                        "consumer_group", CONSUMER_GROUP, "event_type", parsed.eventType).increment();
            }

            log.debug("projected eventId={} eventType={}", parsed.eventId, parsed.eventType);
        } finally {
            sample.stop(projectTimer);
            ack.acknowledge();
        }
    }

    // ============================================================================================
    // Validation — every failure here throws and routes to DLT
    // ============================================================================================

    @SuppressWarnings("unchecked")
    private ParsedEvent validate(Map<String, Object> envelope) {
        String eventId = stringOf(envelope.get("eventId"));
        if (eventId == null || eventId.isBlank()) {
            throw new MalformedEventException("envelope missing eventId");
        }
        String eventType = stringOf(envelope.getOrDefault("eventType", null));
        if (eventType == null) {
            throw new MalformedEventException("envelope missing eventType (eventId=" + eventId + ")");
        }
        Object dataObj = envelope.get("data");
        if (!(dataObj instanceof Map<?, ?>)) {
            throw new MalformedEventException("envelope missing or non-object data block (eventId=" + eventId + ")");
        }
        Map<String, Object> data = (Map<String, Object>) dataObj;

        String transactionId = stringOf(data.get("transactionId"));
        String type = stringOf(data.get("type"));
        String source = stringOf(data.get("sourceAccount"));
        String destination = stringOf(data.get("destinationAccount"));
        Long amount = longOf(data.get("amount"));
        String currency = stringOf(data.get("currency"));
        Instant postedAt = instantOf(data.get("completedAt"));

        if (transactionId == null || type == null || source == null || destination == null
                || amount == null || currency == null) {
            throw new MalformedEventException("event data block is missing required fields (eventId="
                    + eventId + " type=" + type + " transactionId=" + transactionId + ")");
        }

        return new ParsedEvent(eventId, eventType, topicFor(eventType),
                transactionId, type, source, destination, amount, currency, postedAt);
    }

    private List<JournalEntryDocument> rowsFor(ParsedEvent ev, Instant projectedAt) {
        List<JournalEntryDocument> rows = new ArrayList<>(2);
        rows.add(line(deriveLineId(ev.transactionId, "DR"), ev, ev.source,      "DEBIT",  projectedAt));
        rows.add(line(deriveLineId(ev.transactionId, "CR"), ev, ev.destination, "CREDIT", projectedAt));
        return rows;
    }

    /** Stable, deterministic line id — makes projection upserts idempotent across retries. */
    private static String deriveLineId(String transactionId, String suffix) {
        String stripped = transactionId.startsWith("TX-") ? transactionId.substring(3) : transactionId;
        return "JL-PROJ-" + stripped + "-" + suffix;
    }

    private static JournalEntryDocument line(String id, ParsedEvent ev, String account,
                                             String side, Instant projectedAt) {
        JournalEntryDocument doc = new JournalEntryDocument();
        doc.setId(id);
        doc.setTransactionId(ev.transactionId);
        doc.setTransactionType(ev.type);
        doc.setAccount(account);
        doc.setCoaAccount("2100." + account);
        doc.setSide(side);
        doc.setAmount(ev.amount);
        doc.setCurrency(ev.currency);
        doc.setPostedAt(ev.postedAt != null ? ev.postedAt : projectedAt);
        doc.setProjectedAt(projectedAt);
        return doc;
    }

    private static String topicFor(String eventType) {
        return switch (eventType) {
            case "TransactionCompletedEvent" -> "transactions.transfer.completed";
            case "TransactionReversedEvent" -> "transactions.transfer.reversed";
            default -> "unknown";
        };
    }

    private static String stringOf(Object o) { return o == null ? null : o.toString(); }

    private static Long longOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Instant instantOf(Object o) {
        if (o == null) return null;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return null; }
    }

    /** Parsed + validated event payload. Internal value type — never crosses a network boundary. */
    private record ParsedEvent(String eventId, String eventType, String topic,
                                String transactionId, String type,
                                String source, String destination,
                                long amount, String currency, Instant postedAt) { }
}
