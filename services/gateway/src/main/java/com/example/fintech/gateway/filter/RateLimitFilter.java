package com.example.fintech.gateway.filter;

import com.example.fintech.gateway.ratelimit.RateLimitDecision;
import com.example.fintech.gateway.ratelimit.RateLimitKeyResolver;
import com.example.fintech.gateway.ratelimit.RateLimitProperties;
import com.example.fintech.gateway.ratelimit.RedisRateLimitClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-route token-bucket rate limiter.
 *
 * <p>See {@code gateway.spec.md} §3.2 (order 2) and §4.2. Every request consumes a token from
 * an IP bucket; authenticated requests additionally consume from a user-route bucket. Either
 * empty bucket short-circuits with {@code 429 RATE_LIMITED}, a Problem Detail body and the
 * standard {@code X-RateLimit-*} + {@code Retry-After} headers.
 *
 * <p>Implemented as a <strong>{@link WebFilter}</strong> ordered at
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER - 1} so it runs <em>before</em> the Spring
 * Security reactive filter chain. This lets the anon (IP) bucket protect against unauthenticated
 * DoS even on routes that would otherwise 401 first — which is the whole point of an
 * edge-level rate limiter. (A {@code GlobalFilter} would run inside the gateway's
 * {@code FilteringWebHandler}, which is invoked <em>after</em> all {@code WebFilter}s, including
 * Spring Security.)
 *
 * <p>Because route matching also happens later in the pipeline, the bucket's route component is
 * derived from the URL path in {@link RateLimitKeyResolver#routeIdFromPath(String)} — kept in
 * sync with the route table in {@code application.yaml} §3.1.
 */
@Component
public class RateLimitFilter implements WebFilter, Ordered {

    // Spring Security's reactive WebFilterChainProxy is registered at order -100; we run just
    // ahead of it so the anon (IP) bucket can short-circuit unauthenticated traffic before
    // Spring Security 401s a request that hasn't even paid for its bucket token yet.
    public static final int ORDER = -101;

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBLEM_TYPE = "https://api.example.com/problems/rate-limited";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final RedisRateLimitClient client;
    private final RateLimitKeyResolver resolver;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RedisRateLimitClient client,
                           RateLimitKeyResolver resolver,
                           RateLimitProperties properties,
                           MeterRegistry meterRegistry) {
        this.client = client;
        this.resolver = resolver;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isBypassed(exchange)) {
            return chain.filter(exchange);
        }

        RateLimitProperties.Defaults defaults = properties.getDefaults();
        String ipBucket = resolver.anonKey(exchange);
        Optional<String> userBucket = resolver.userKey(exchange);

        Mono<RateLimitDecision> ipCheck = client.tryConsume(ipBucket, defaults.getAnon());

        Mono<RateLimitDecision> userCheck = userBucket
                .map(key -> client.tryConsume(key, resolver.userBucket(exchange, defaults)))
                .orElseGet(Mono::empty);

        return ipCheck.flatMap(ipDecision -> {
            if (!ipDecision.allowed()) {
                return reject(exchange, ipDecision);
            }
            return userCheck
                    .flatMap(userDecision -> userDecision.allowed()
                            ? proceed(exchange, chain, ipDecision, userDecision)
                            : reject(exchange, userDecision))
                    .switchIfEmpty(Mono.defer(() -> proceed(exchange, chain, ipDecision, null)));
        }).onErrorResume(ex -> {
            // Fail open: if Redis is unreachable, log + allow rather than block all traffic.
            log.warn("rate-limit check failed; allowing request: {}", ex.toString());
            return chain.filter(exchange);
        });
    }

    private static boolean isBypassed(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.startsWith("/actuator");
    }

    private Mono<Void> proceed(ServerWebExchange exchange,
                               WebFilterChain chain,
                               RateLimitDecision ip,
                               RateLimitDecision user) {
        RateLimitDecision tightest = (user != null && user.remaining() < ip.remaining()) ? user : ip;
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-RateLimit-Limit", Integer.toString(tightest.capacity()));
        headers.set("X-RateLimit-Remaining", Long.toString(Math.max(0, tightest.remaining())));
        headers.set("X-RateLimit-Reset", Long.toString(tightest.resetAtMs() / 1000));
        headers.set("X-RateLimit-Bucket", tightest.bucket());
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, RateLimitDecision decision) {
        Counter.builder("gateway_rate_limit_rejections_total")
                .tag("bucket", bucketTag(decision.bucket()))
                .tag("route", RateLimitKeyResolver.routeIdFromPath(exchange.getRequest().getPath().value()))
                .register(meterRegistry)
                .increment();

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.valueOf("application/problem+json"));
        long retryAfterSec = Math.max(1, (decision.retryAfterMs() + 999) / 1000);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSec));
        response.getHeaders().set("X-RateLimit-Limit", Integer.toString(decision.capacity()));
        response.getHeaders().set("X-RateLimit-Remaining", "0");
        response.getHeaders().set("X-RateLimit-Reset", Long.toString(decision.resetAtMs() / 1000));
        response.getHeaders().set("X-RateLimit-Bucket", decision.bucket());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", PROBLEM_TYPE);
        body.put("code", "RATE_LIMITED");
        body.put("title", "Too Many Requests");
        body.put("status", 429);
        body.put("detail", "Rate limit exceeded for bucket " + decision.bucket());
        body.put("instance", exchange.getRequest().getPath().value());
        // We run before CorrelationIdGlobalFilter, so MDC is empty here — read the inbound header.
        body.put("correlationId", exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER));
        body.put("timestamp", Instant.now().toString());
        body.put("params", Map.of(
                "available", 0,
                "limit", decision.capacity(),
                "resetAt", Instant.ofEpochMilli(decision.resetAtMs()).toString(),
                "bucket", decision.bucket()));

        DataBuffer buffer = response.bufferFactory().wrap(JSON.writeValueAsBytes(body));
        return response.writeWith(Mono.just(buffer));
    }

    private static String bucketTag(String bucket) {
        // Tag cardinality: collapse to the bucket family (anon, user-default, ...).
        if (bucket.startsWith("anon:")) return "anon";
        if (bucket.startsWith("user:") && bucket.endsWith(":POST") && bucket.contains(":transactions:")) {
            return "user-transactions-write";
        }
        if (bucket.startsWith("user:") && bucket.endsWith(":POST") && bucket.contains(":auth-sessions:")) {
            return "user-sessions-post";
        }
        return "user-default";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
