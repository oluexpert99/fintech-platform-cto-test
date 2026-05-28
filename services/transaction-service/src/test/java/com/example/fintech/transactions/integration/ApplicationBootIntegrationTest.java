package com.example.fintech.transactions.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the Spring context boots end-to-end against real Mongo + Kafka via Testcontainers.
 *
 * <p>This proves the toolchain — POM, configs, SchemaInitializer wiring, repository wiring,
 * security config — is structurally correct.
 */
class ApplicationBootIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void springContextLoads() {
        assertThat(context).isNotNull();
        // TransferService + ReversalService were merged into TransactionWriteService (CQRS shape).
        assertThat(context.containsBean("transactionWriteService")).isTrue();
        assertThat(context.containsBean("outboxPublisher")).isTrue();
    }
}
