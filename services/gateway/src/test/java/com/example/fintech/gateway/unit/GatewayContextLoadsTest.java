package com.example.fintech.gateway.unit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test that the Spring context loads. Disables the OAuth2 resource server's JWT decoding
 * for the test context so we don't need a running Keycloak just to verify the wiring compiles
 * and beans resolve.
 */
@SpringBootTest
class GatewayContextLoadsTest {

    @DynamicPropertySource
    static void disableOAuth2(DynamicPropertyRegistry registry) {
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive."
                        + "ReactiveOAuth2ResourceServerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("correlationIdGlobalFilter")).isTrue();
    }
}
