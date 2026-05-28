package com.example.fintech.auth.persistence.repository;

import com.example.fintech.auth.persistence.document.SessionDocument;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends MongoRepository<SessionDocument, String> {
    List<SessionDocument> findByUserIdOrderByLastSeenAtDesc(String userId, Limit limit);
    Optional<SessionDocument> findByKeycloakSession(String keycloakSession);
}
