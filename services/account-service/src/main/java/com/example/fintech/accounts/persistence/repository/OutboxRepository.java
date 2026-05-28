package com.example.fintech.accounts.persistence.repository;

import com.example.fintech.accounts.persistence.document.OutboxRecordDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboxRepository extends MongoRepository<OutboxRecordDocument, String>, OutboxRepositoryCustom {
}
