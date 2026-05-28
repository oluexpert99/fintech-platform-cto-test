package com.example.fintech.accounting.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableMongoRepositories(basePackages = "com.example.fintech.accounting.persistence.repository")
public class MongoConfig {
    // Accounting-service does not need a MongoTransactionManager — all writes are isolated to the
    // chart_of_accounts and inbox_accounting collections; multi-doc transactions aren't required.
}
