package com.example.fintech.accounting;

import com.example.fintech.accounting.persistence.document.ChartOfAccountsDocument;
import com.example.fintech.accounting.persistence.repository.ChartOfAccountsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots accounting-service against a real Mongo via Testcontainers. Asserts:
 *  - context loads
 *  - SchemaInitializer seeded the chart_of_accounts with all 8 system accounts
 *  - the resolver-via-bean wiring is in place
 */
@SpringBootTest
@Testcontainers
class AccountingContextLoadsTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // accounting-service now owns its own database (fintech_accounting)
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("fintech_accounting"));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired private ApplicationContext context;
    @Autowired private ChartOfAccountsRepository coaRepo;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("trialBalanceCalculator")).isTrue();
        assertThat(context.containsBean("journalFinder")).isTrue();
        assertThat(context.containsBean("coaTypeResolver")).isTrue();
        // The real projector replaces the no-op listener
        assertThat(context.containsBean("transactionEventsProjector")).isTrue();
        // Reconciliation job is now wired
        assertThat(context.containsBean("reconciliationJob")).isTrue();
    }

    @Test
    void chartOfAccountsSeeded() {
        List<ChartOfAccountsDocument> all = coaRepo.findAllByOrderByIdAsc();
        assertThat(all).hasSize(8);
        assertThat(all).extracting(ChartOfAccountsDocument::getId)
                .containsExactly("1000", "1100", "2100", "3000", "4000", "4100", "5000", "5100");
        assertThat(all).allMatch(ChartOfAccountsDocument::isSystem);
        assertThat(all).extracting(ChartOfAccountsDocument::getType)
                .contains("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");
    }
}
