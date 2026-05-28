package com.example.fintech.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bucket capacities and refill rates for the gateway rate limiter.
 *
 * <p>See {@code gateway.spec.md} §4.2. Each bucket is a (capacity, refill-per-second) pair
 * driving the token-bucket Lua script in {@code RedisRateLimitClient}.
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    private Defaults defaults = new Defaults();

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public static class Defaults {
        private Bucket anon = new Bucket(60, 1.0);
        private Bucket userTransactionsWrite = new Bucket(60, 1.0);
        private Bucket userSessionsPost = new Bucket(10, 10.0 / 60.0);
        private Bucket userDefault = new Bucket(6000, 100.0);

        public Bucket getAnon() { return anon; }
        public void setAnon(Bucket anon) { this.anon = anon; }
        public Bucket getUserTransactionsWrite() { return userTransactionsWrite; }
        public void setUserTransactionsWrite(Bucket b) { this.userTransactionsWrite = b; }
        public Bucket getUserSessionsPost() { return userSessionsPost; }
        public void setUserSessionsPost(Bucket b) { this.userSessionsPost = b; }
        public Bucket getUserDefault() { return userDefault; }
        public void setUserDefault(Bucket b) { this.userDefault = b; }
    }

    public static class Bucket {
        private int capacity;
        private double refillPerSecond;

        public Bucket() {}

        public Bucket(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public double getRefillPerSecond() { return refillPerSecond; }
        public void setRefillPerSecond(double refillPerSecond) { this.refillPerSecond = refillPerSecond; }
    }
}
