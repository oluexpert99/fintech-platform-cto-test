package com.example.fintech.accounting.config;

import com.mongodb.ConnectionString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableMongoRepositories(basePackages = "com.example.fintech.accounting.persistence.repository")
public class MongoConfig {
    // Accounting-service does not need a MongoTransactionManager — all writes are isolated to the
    // chart_of_accounts and inbox_accounting collections; multi-doc transactions aren't required.

    /**
     * Supplies the Mongo connection from the {@code MONGO_URI} env var.
     *
     * <p>Works around a property-binding issue under Spring Boot 4.0.6 where
     * {@code spring.data.mongodb.uri} (and host/port) are not applied to the
     * auto-configured MongoClient — verified against env vars, SPRING_APPLICATION_JSON
     * and command-line args, all of which left the client on the localhost default.
     * Providing a {@link MongoConnectionDetails} bean is the same mechanism the
     * integration tests use via Testcontainers {@code @ServiceConnection}, and it
     * bypasses the broken binding.
     *
     * <p>Gated on {@code MONGO_URI} so tests — which inject their own
     * {@code MongoConnectionDetails} via {@code @ServiceConnection} and do not set
     * {@code MONGO_URI} — are unaffected.
     */
    @Bean
    @ConditionalOnProperty(name = "MONGO_URI")
    MongoConnectionDetails mongoConnectionDetails(@Value("${MONGO_URI}") String uri) {
        ConnectionString connectionString = new ConnectionString(uri);
        return () -> connectionString;
    }
}
