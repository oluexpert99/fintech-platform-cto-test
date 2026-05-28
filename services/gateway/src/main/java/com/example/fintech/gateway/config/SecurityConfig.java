package com.example.fintech.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Reactive Spring Security for the gateway.
 *
 * <p>Routes that need no auth (anonymous registration, login, OAuth2 token endpoint, health,
 * Prometheus) are permitted explicitly; everything else requires a valid JWT validated against
 * Keycloak's JWKS (configured via {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}).
 *
 * <p>CORS is enabled so the browser-based {@code platform-ui} (default {@code http://localhost:5173})
 * can call the gateway from a different origin. The allowed origins are configurable via
 * {@code app.cors.allowed-origins} (comma-separated).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(authz -> authz
                        // CORS preflight must never require auth.
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
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

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // The platform-ui sends a Bearer token, JSON, and an Idempotency-Key on writes.
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        // Headers the UI may want to read off the response.
        cors.setExposedHeaders(List.of(
                "X-Correlation-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                "X-RateLimit-Reset", "X-RateLimit-Bucket", "Retry-After", "Location"));
        // Auth is via a Bearer token in the Authorization header, not cookies — no credentials needed.
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
