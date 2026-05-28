package com.example.fintech.accounting.persistence.repository;

import com.example.fintech.accounting.persistence.document.JournalEntryDocument;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;

public interface JournalEntryRepository extends MongoRepository<JournalEntryDocument, String> {

    // First page (no cursor)
    List<JournalEntryDocument> findAllByOrderByIdDesc(Limit limit);

    // Subsequent page after the cursor
    List<JournalEntryDocument> findByIdLessThanOrderByIdDesc(String afterId, Limit limit);

    // Filtered variants
    List<JournalEntryDocument> findByAccountOrderByIdDesc(String account, Limit limit);
    List<JournalEntryDocument> findByAccountAndIdLessThanOrderByIdDesc(String account, String afterId, Limit limit);

    List<JournalEntryDocument> findByTransactionIdOrderByIdDesc(String transactionId, Limit limit);

    @Query("{ 'postedAt': { '$lte': ?0 } }")
    long countByPostedAtUpTo(Instant asOf);
}
