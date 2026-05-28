package com.example.fintech.gateway.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: real Redis (Testcontainers), real Spring Cloud Gateway, downstream is a
 * black-holed address (connection refused), so we observe only the rate-limit filter's behaviour.
 *
 * <p>Asserts the spec's acceptance criterion: with anon-bucket capacity {@code N}, the
 * {@code N+1}-th request returns {@code 429 RATE_LIMITED} with {@code Retry-After} +
 * {@code X-RateLimit-*} headers and a Problem Detail body.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class RateLimitIntegrationTest {

    private static final int ANON_CAPACITY = 5;

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // No-op downstream: route resolves but connects to a refused port. The rate-limit filter
        // still decrements the bucket before the routing filter attempts the connection.
        registry.add("AUTH_SERVICE_URL",        () -> "http://127.0.0.1:1");
        registry.add("ACCOUNT_SERVICE_URL",     () -> "http://127.0.0.1:1");
        registry.add("TRANSACTION_SERVICE_URL", () -> "http://127.0.0.1:1");
        registry.add("ACCOUNTING_SERVICE_URL",  () -> "http://127.0.0.1:1");

        // Skip Keycloak — `/v1/users` is permitAll, but the OAuth2 autoconfig still tries to fetch
        // the JWKS at boot. Disable it for this test.
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive."
                        + "ReactiveOAuth2ResourceServerAutoConfiguration");

        // Tight bucket so the test is fast.
        registry.add("ratelimit.defaults.anon.capacity",          () -> ANON_CAPACITY);
        registry.add("ratelimit.defaults.anon.refill-per-second", () -> 0.1);
    }

    @Autowired
    WebTestClient client;

    @Autowired
    ReactiveStringRedisTemplate redis;

    @Test
    void anonBucketRejectsAfterCapacityExhausted() {
        // Clean slate per test.
        redis.getConnectionFactory().getReactiveConnection().serverCommands().flushDb().block();

        for (int i = 0; i < ANON_CAPACITY; i++) {
            client.post().uri("/v1/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    // Downstream is 127.0.0.1:1 → gateway turns it into a 5xx Problem Detail.
                    // The point: it is NOT a 429.
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }

        EntityExchangeResult<byte[]> rejected = client.post().uri("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().exists("X-RateLimit-Limit")
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectHeader().exists("X-RateLimit-Bucket")
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody().returnResult();

        String body = new String(rejected.getResponseBody());
        assertThat(body).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(body).contains("\"status\":429");
        assertThat(rejected.getResponseHeaders().getFirst("X-RateLimit-Limit"))
                .isEqualTo(Integer.toString(ANON_CAPACITY));
        assertThat(rejected.getResponseHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getResponseHeaders().getFirst("X-RateLimit-Bucket")).startsWith("anon:");
    }
}
