package com.example.fintech.transactions.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the Spring context boots end-to-end against real Mongo + Kafka via Testcontainers.
 *
 * <p>This proves the toolchain — POM, configs, Mongock migrations, repository wiring, security
 * config — is structurally correct before any business logic is implemented. The §5.2 scenarios
 * from {@code transaction-service.spec} land in follow-up commits as the transfer flow is wired.
 */
class ApplicationBootIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void springContextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("transferService")).isTrue();
        assertThat(context.containsBean("outboxPublisher")).isTrue();
    }
}
