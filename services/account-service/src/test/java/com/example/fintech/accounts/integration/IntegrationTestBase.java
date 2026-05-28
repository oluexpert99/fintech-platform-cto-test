package com.example.fintech.accounts.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestBase {

    @Container
    @ServiceConnection
    protected static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));
    // Note: Kafka is NOT wired via @ServiceConnection. Spring Boot 4's
    // KafkaContainerConnectionDetailsFactory only matches the newer
    // org.testcontainers.kafka.{KafkaContainer,ConfluentKafkaContainer} classes,
    // not the deprecated org.testcontainers.containers.KafkaContainer. We bind
    // spring.kafka.bootstrap-servers via @DynamicPropertySource below.
    @Container
    protected static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Mongo via @ServiceConnection. Kafka bootstrap-servers below (see comment above).
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://disabled-for-tests");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration");
    }
}
