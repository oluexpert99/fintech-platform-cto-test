package com.example.fintech.auth.messaging;

import com.example.fintech.auth.persistence.document.OutboxRecordDocument;
import com.example.fintech.auth.persistence.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Auth-service outbox publisher. Identical pattern to transaction-service:
 * lease-claim PENDING rows → publish to Kafka → mark SENT.
 *
 * <p>The class lives in this service (not shared) per the locked codebase-structure decision
 * (no shared library) — the small duplication is the cost of decoupling.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration leaseDuration;
    private final String instanceId = "outbox-auth-" + UUID.randomUUID();

    public OutboxPublisher(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${outbox.publisher.batch-size:50}") int batchSize,
            @Value("${outbox.publisher.max-attempts:10}") int maxAttempts,
            @Value("${outbox.publisher.lease-duration-seconds:5}") long leaseDurationSeconds) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.leaseDuration = Duration.ofSeconds(leaseDurationSeconds);
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.tick-millis:300}")
    public void drain() {
        List<OutboxRecordDocument> batch = outboxRepository.claimPending(batchSize, leaseDuration, instanceId);
        if (batch.isEmpty()) return;
        for (OutboxRecordDocument record : batch) publish(record);
    }

    private void publish(OutboxRecordDocument record) {
        try {
            kafkaTemplate.send(record.getTopic(), record.getAggregateId(), record.getPayload()).get();
            outboxRepository.markSent(record.getId());
            meterRegistry.counter("outbox.published.total", "service", "auth", "topic", record.getTopic(), "outcome", "success").increment();
        } catch (Exception e) {
            int newAttempts = record.getAttempts() + 1;
            String msg = e.getMessage();
            if (newAttempts >= maxAttempts) {
                outboxRepository.markPoisoned(record.getId(), msg);
                meterRegistry.counter("outbox.poisoned.total", "service", "auth", "topic", record.getTopic()).increment();
                log.error("Outbox row {} poisoned after {} attempts: {}", record.getId(), newAttempts, msg);
            } else {
                outboxRepository.incrementAttempts(record.getId(), msg);
                meterRegistry.counter("outbox.published.total", "service", "auth", "topic", record.getTopic(), "outcome", "failure").increment();
                log.warn("Outbox send failed row={} (attempt {}): {}", record.getId(), newAttempts, msg);
            }
        }
    }
}
