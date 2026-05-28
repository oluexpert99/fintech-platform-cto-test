package com.example.fintech.transactions.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for every integration test in this module.
 *
 * <p>Per {@code [[feedback-testcontainers]]}: real binaries via Testcontainers — no embedded Mongo,
 * no {@code EmbeddedKafkaBroker}, no in-memory substitutes. Containers are static so they start
 * once per Surefire fork and are shared across tests; per-test isolation is handled by collection
 * drops between tests, not per-test container restarts.
 *
 * <p><strong>Mongo replica-set fidelity:</strong> {@link MongoDBContainer} starts a single-node
 * replica set ({@code rs0}). This is sufficient for testing the critical correctness paths:
 * multi-document transactions, write-conflict retries, conditional updates, optimistic locking,
 * and unique-index races (the {@code Idempotency-Key} arbiter). It does <em>not</em> exercise
 * cross-node election semantics or network-partition behaviour — those would require the silaev
 * mongodb-replica-set library or three explicitly-composed containers and are appropriate for a
 * separate {@code chaos-it} profile, not the default suite. The integration tests in this
 * package cover correctness, not failover.
 *
 * <p>Pinned versions (see {@code transaction-service.spec} §5.0): no {@code :latest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Testcontainers
public abstract class IntegrationTestBase {

    protected static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));

    protected static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    static {
        MONGO.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("fintech"));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Disable JWT validation for tests that don't need it. Tests requiring real Keycloak
        // start their own container and override these properties.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://disabled-for-tests");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration");
    }
}
