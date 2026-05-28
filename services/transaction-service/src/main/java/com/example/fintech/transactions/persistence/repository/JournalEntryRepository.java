package com.example.fintech.transactions.persistence.repository;

import com.example.fintech.transactions.persistence.document.JournalEntryDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface JournalEntryRepository extends MongoRepository<JournalEntryDocument, String> {

    List<JournalEntryDocument> findByTransactionId(String transactionId);
}
