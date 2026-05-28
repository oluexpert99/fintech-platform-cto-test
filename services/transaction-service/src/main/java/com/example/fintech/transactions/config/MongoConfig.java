package com.example.fintech.transactions.config;

import com.mongodb.ConnectionString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Wires the {@link MongoTransactionManager} so {@code @Transactional} works for multi-document
 * transactions on the replica set.
 *
 * <p>Required by {@code TransferService} and {@code ReversalService}. Without this, {@code @Transactional}
 * would silently fall back to JDBC's transaction manager or none at all.
 *
 * <p>Spring Boot auto-configures {@link MongoDatabaseFactory} from {@code spring.data.mongodb.uri}.
 */
@Configuration
@EnableMongoAuditing
@EnableMongoRepositories(basePackages = "com.example.fintech.transactions.persistence.repository")
public class MongoConfig {

    @Bean
    public MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory);
    }

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
