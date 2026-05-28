package com.example.fintech.accounts.persistence.repository;

import com.example.fintech.accounts.persistence.document.AccountDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AccountRepository extends MongoRepository<AccountDocument, String>, AccountRepositoryCustom {
    Optional<AccountDocument> findByIdempotencyKey(String idempotencyKey);
    Page<AccountDocument> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId, Pageable pageable);
}
