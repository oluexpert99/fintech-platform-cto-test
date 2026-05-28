package com.example.fintech.auth.persistence.repository;

import com.example.fintech.auth.persistence.document.OutboxRecordDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboxRepository
        extends MongoRepository<OutboxRecordDocument, String>, OutboxRepositoryCustom {
}
