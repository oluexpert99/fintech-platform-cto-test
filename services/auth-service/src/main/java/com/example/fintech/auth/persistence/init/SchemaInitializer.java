package com.example.fintech.auth.persistence.init;

import com.example.fintech.auth.persistence.document.OutboxRecordDocument;
import com.example.fintech.auth.persistence.document.SessionDocument;
import com.example.fintech.auth.persistence.document.UserDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final MongoTemplate mongoTemplate;

    public SchemaInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialise() {
        log.info("Initialising MongoDB indexes for auth-service");
        IndexOperations users = mongoTemplate.indexOps(UserDocument.class);
        users.createIndex(new Index().on("email", Sort.Direction.ASC).unique());
        users.createIndex(new Index().on("keycloakSub", Sort.Direction.ASC).unique());

        IndexOperations sessions = mongoTemplate.indexOps(SessionDocument.class);
        sessions.createIndex(new Index().on("userId", Sort.Direction.ASC).on("lastSeenAt", Sort.Direction.DESC));
        sessions.createIndex(new Index().on("keycloakSession", Sort.Direction.ASC));
        sessions.createIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0L));

        IndexOperations outbox = mongoTemplate.indexOps(OutboxRecordDocument.class);
        outbox.createIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("leaseUntil", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC));
        outbox.createIndex(new Index().on("eventId", Sort.Direction.ASC).unique());
        outbox.createIndex(new Index().on("expireAt", Sort.Direction.ASC).expire(0L));
        log.info("MongoDB indexes initialised");
    }
}
