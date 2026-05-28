package com.example.fintech.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the auth-service context against a real Mongo via Testcontainers. JWT validation is
 * disabled (no Keycloak in this test) so the wiring can be verified without external services.
 *
 * <p>Mongo is wired via {@link ServiceConnection} — this provides a {@code MongoConnectionDetails}
 * bean that takes precedence over {@code application.yaml}'s {@code spring.data.mongodb.uri}
 * default and sidesteps the {@code @DynamicPropertySource} ordering trap that previously caused
 * the client to fall back to {@code localhost:27017}.
 */
@SpringBootTest
@Testcontainers
class AuthContextLoadsTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // Mongo is wired by @ServiceConnection above. Only non-Mongo overrides go here.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("registerUserService")).isTrue();
        assertThat(context.containsBean("loginService")).isTrue();
        assertThat(context.containsBean("sessionService")).isTrue();
    }
}
