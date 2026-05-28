package com.example.fintech.gateway.ratelimit;

/**
 * Result of a single bucket evaluation.
 *
 * @param allowed       whether the request consumed a token successfully
 * @param remaining     tokens left in the bucket after this evaluation
 * @param capacity      bucket capacity (echoed for response headers)
 * @param retryAfterMs  if rejected, milliseconds until at least one token is available
 * @param resetAtMs     epoch millis at which the bucket would be full again at current rate
 * @param bucket        bucket identifier (for metrics + the X-RateLimit-Bucket header)
 */
public record RateLimitDecision(
        boolean allowed,
        long remaining,
        int capacity,
        long retryAfterMs,
        long resetAtMs,
        String bucket
) {
}
