package com.example.fintech.accounting.persistence.repository;

import com.example.fintech.accounting.persistence.document.ChartOfAccountsDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ChartOfAccountsRepository extends MongoRepository<ChartOfAccountsDocument, String> {
    List<ChartOfAccountsDocument> findAllByOrderByIdAsc();
}
