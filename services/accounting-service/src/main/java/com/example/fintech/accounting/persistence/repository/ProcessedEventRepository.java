package com.example.fintech.accounting.persistence.repository;

import com.example.fintech.accounting.persistence.document.ProcessedEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedEventRepository extends MongoRepository<ProcessedEventDocument, String> {
}
