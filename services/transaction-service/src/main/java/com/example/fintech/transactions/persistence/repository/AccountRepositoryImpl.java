package com.example.fintech.transactions.persistence.repository;

import com.example.fintech.transactions.domain.model.AccountId;
import com.example.fintech.transactions.persistence.document.AccountDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

/**
 * Spring Data MongoDB custom-repository implementation.
 *
 * <p>The class name MUST be {@code AccountRepositoryImpl} (the {@code Impl} suffix) so Spring Data
 * picks it up as the fragment implementing {@link AccountRepositoryCustom}.
 *
 * <p>TODO(spec §4.1 step 3a / 3b): the conditional-update queries below are the structurally
 * correct skeleton. Reviewer should confirm field names match {@code AccountDocument}.
 */
public class AccountRepositoryImpl implements AccountRepositoryCustom {

    private static final String ACTIVE = "ACTIVE";

    private final MongoTemplate mongoTemplate;

    public AccountRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean conditionalDebit(AccountId source, long amount, long expectedVersion) {
        Query query = new Query(Criteria.where("_id").is(source.value())
                .and("status").is(ACTIVE)
                .and("version").is(expectedVersion)
                .and("balance").gte(amount));
        Update update = new Update()
                .inc("balance", -amount)
                .inc("version", 1L)
                .set("updatedAt", Instant.now());
        return mongoTemplate.updateFirst(query, update, AccountDocument.class).getMatchedCount() == 1;
    }

    @Override
    public boolean conditionalCredit(AccountId destination, long amount, long expectedVersion) {
        Query query = new Query(Criteria.where("_id").is(destination.value())
                .and("status").is(ACTIVE)
                .and("version").is(expectedVersion));
        Update update = new Update()
                .inc("balance", amount)
                .inc("version", 1L)
                .set("updatedAt", Instant.now());
        return mongoTemplate.updateFirst(query, update, AccountDocument.class).getMatchedCount() == 1;
    }
}
