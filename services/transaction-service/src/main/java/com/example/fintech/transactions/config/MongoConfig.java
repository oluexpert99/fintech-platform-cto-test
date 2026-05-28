package com.example.fintech.transactions.config;

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
}
