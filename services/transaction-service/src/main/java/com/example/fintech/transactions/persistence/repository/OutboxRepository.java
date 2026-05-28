package com.example.fintech.transactions.persistence.repository;

import com.example.fintech.transactions.persistence.document.OutboxRecordDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboxRepository
        extends MongoRepository<OutboxRecordDocument, String>, OutboxRepositoryCustom {
}
