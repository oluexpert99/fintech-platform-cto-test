package com.example.fintech.accounts.messaging;

import com.example.fintech.accounts.persistence.document.OutboxRecordDocument;
import com.example.fintech.accounts.persistence.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxPublisher {
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration leaseDuration;
    private final String instanceId = "outbox-" + UUID.randomUUID();

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           MeterRegistry meterRegistry,
                           @Value("${outbox.publisher.batch-size:50}") int batchSize,
                           @Value("${outbox.publisher.max-attempts:10}") int maxAttempts,
                           @Value("${outbox.publisher.lease-duration-seconds:5}") long leaseSeconds) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.tick-millis:300}")
    public void drain() {
        List<OutboxRecordDocument> batch = outboxRepository.claimPending(batchSize, leaseDuration, instanceId);
        for (OutboxRecordDocument record : batch) {
            publish(record);
        }
    }

    private void publish(OutboxRecordDocument record) {
        try {
            kafkaTemplate.send(record.getTopic(), record.getAggregateId(), record.getPayload()).get();
            outboxRepository.markSent(record.getId());
            meterRegistry.counter("outbox.published.total", "topic", record.getTopic(), "outcome", "success").increment();
        } catch (Exception e) {
            int attempts = record.getAttempts() + 1;
            if (attempts >= maxAttempts) {
                outboxRepository.markPoisoned(record.getId(), e.getMessage());
            } else {
                outboxRepository.incrementAttempts(record.getId(), e.getMessage());
            }
            meterRegistry.counter("outbox.published.total", "topic", record.getTopic(), "outcome", "failure").increment();
        }
    }
}
