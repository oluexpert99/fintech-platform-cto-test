package com.example.fintech.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CsrfWebFilter;

/**
 * Reactive Spring Security for the gateway.
 *
 * <p>Routes that need no auth (anonymous registration, login, OAuth2 token endpoint, health,
 * Prometheus) are permitted explicitly; everything else requires a valid JWT validated against
 * Keycloak's JWKS (configured via {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(authz -> authz
                        // Anonymous public endpoints
                        .pathMatchers("/v1/users").permitAll()                 // POST register
                        .pathMatchers("/v1/sessions").permitAll()              // POST login
                        .pathMatchers("/v1/oauth/token").permitAll()           // OAuth2 token endpoint
                        // Health + metrics on the gateway's own ports
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Everything else needs a valid JWT
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
