package com.example.fintech.accounts.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationBootIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void springContextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("accountWriteService")).isTrue();
        assertThat(context.containsBean("accountReadService")).isTrue();
        assertThat(context.containsBean("outboxPublisher")).isTrue();
    }
}
