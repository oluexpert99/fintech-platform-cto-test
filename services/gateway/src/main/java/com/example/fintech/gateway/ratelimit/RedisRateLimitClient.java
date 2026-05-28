package com.example.fintech.gateway.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Atomic token-bucket evaluation backed by a Redis Lua script.
 *
 * <p>The script lives at {@code ratelimit/token_bucket.lua} and returns
 * {@code [allowed, remaining, retryAfterMs, resetAtMs]} so the caller can build
 * standard {@code X-RateLimit-*} headers and a {@code Retry-After} hint.
 */
@Component
public class RedisRateLimitClient {

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> script;

    public RedisRateLimitClient(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.script = RedisScript.of(
                new ClassPathResource("ratelimit/token_bucket.lua"), List.class);
    }

    public Mono<RateLimitDecision> tryConsume(String bucket, RateLimitProperties.Bucket cfg) {
        long now = System.currentTimeMillis();
        return redis.execute(
                        script,
                        List.of(bucket),
                        List.of(
                                Long.toString(now),
                                Integer.toString(cfg.getCapacity()),
                                Double.toString(cfg.getRefillPerSecond()),
                                "1"))
                .next()
                .map(raw -> toDecision(raw, cfg, bucket));
    }

    @SuppressWarnings("unchecked")
    private static RateLimitDecision toDecision(Object raw, RateLimitProperties.Bucket cfg, String bucket) {
        List<Object> result = (List<Object>) raw;
        boolean allowed = asLong(result.get(0)) == 1L;
        long remaining = asLong(result.get(1));
        long retryAfterMs = asLong(result.get(2));
        long resetAtMs = asLong(result.get(3));
        return new RateLimitDecision(allowed, remaining, cfg.getCapacity(), retryAfterMs, resetAtMs, bucket);
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
}
