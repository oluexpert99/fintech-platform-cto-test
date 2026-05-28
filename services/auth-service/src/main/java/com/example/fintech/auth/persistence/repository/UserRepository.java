package com.example.fintech.auth.persistence.repository;

import com.example.fintech.auth.persistence.document.UserDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<UserDocument, String> {
    Optional<UserDocument> findByEmail(String email);
    Optional<UserDocument> findByKeycloakSub(String keycloakSub);
}
