package com.example.fintech.transactions.persistence.repository;

import com.example.fintech.transactions.persistence.document.AccountDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountRepository extends MongoRepository<AccountDocument, String>, AccountRepositoryCustom {
}
