package com.example.fintech.accounts.persistence.repository;

import com.example.fintech.accounts.persistence.document.AccountDocument;
import com.mongodb.ReadConcern;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

public class AccountRepositoryImpl implements AccountRepositoryCustom {
    private final MongoTemplate mongoTemplate;

    public AccountRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<AccountDocument> findByIdWithMajority(String id) {
        Document raw = mongoTemplate
                .getCollection(mongoTemplate.getCollectionName(AccountDocument.class))
                .withReadConcern(ReadConcern.MAJORITY)
                .find(Filters.eq("_id", id))
                .first();
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(mongoTemplate.getConverter().read(AccountDocument.class, raw));
    }

    @Override
    public Optional<AccountDocument> updateIfVersionMatches(AccountDocument account, long expectedVersion) {
        Query query = new Query(Criteria.where("_id").is(account.getId()).and("version").is(expectedVersion));
        Update update = new Update()
                .set("label", account.getLabel())
                .set("status", account.getStatus())
                .set("statusReason", account.getStatusReason())
                .set("version", account.getVersion())
                .set("updatedAt", account.getUpdatedAt())
                .set("frozenAt", account.getFrozenAt())
                .set("closedAt", account.getClosedAt())
                .set("idempotencyKey", account.getIdempotencyKey())
                .set("payloadHash", account.getPayloadHash());
        FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);
        AccountDocument updated = mongoTemplate.findAndModify(query, update, options, AccountDocument.class);
        return Optional.ofNullable(updated);
    }
}
