package com.example.fintech.auth.application;

import com.example.fintech.auth.api.dto.RegisterUserRequest;
import com.example.fintech.auth.api.dto.UserResponse;
import com.example.fintech.auth.domain.exception.EmailAlreadyRegisteredException;
import com.example.fintech.auth.domain.model.KycLevel;
import com.example.fintech.auth.domain.model.UserId;
import com.example.fintech.auth.domain.model.UserStatus;
import com.example.fintech.auth.integration.KeycloakAdminClient;
import com.example.fintech.auth.persistence.document.OutboxRecordDocument;
import com.example.fintech.auth.persistence.document.UserDocument;
import com.example.fintech.auth.persistence.repository.OutboxRepository;
import com.example.fintech.auth.persistence.repository.UserRepository;
import com.github.f4b6a3.ulid.UlidCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class RegisterUserService {

    private static final Logger log = LoggerFactory.getLogger(RegisterUserService.class);
    private static final String EVENT_TYPE = "UserRegisteredEvent";
    private static final String TOPIC = "users.user.registered";

    private final KeycloakAdminClient keycloak;
    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;

    public RegisterUserService(KeycloakAdminClient keycloak,
                                UserRepository userRepository,
                                OutboxRepository outboxRepository) {
        this.keycloak = keycloak;
        this.userRepository = userRepository;
        this.outboxRepository = outboxRepository;
    }

    public UserResponse register(RegisterUserRequest request) {
        // Step 1 — create at Keycloak (outside the Mongo TX; Keycloak owns its DB)
        String keycloakSub = keycloak.createUser(
                request.email(), request.password(), request.fullName(), request.phone());

        // Step 2 — record locally + emit outbox event in one Mongo TX
        try {
            return persistAndPublish(request, keycloakSub);
        } catch (DuplicateKeyException e) {
            // Rare race: Mongo's email-unique-index lost; compensate by deleting the Keycloak user.
            // The "race" is essentially impossible in practice (Keycloak checks uniqueness first),
            // but we belt-and-brace it because the cost of a half-created user is high.
            try {
                keycloak.revokeAllSessions(keycloakSub);
                // TODO: full compensation = call Keycloak admin DELETE user. Out of scope for the scaffold.
            } catch (Exception ignored) {}
            throw new EmailAlreadyRegisteredException(request.email());
        }
    }

    @Transactional
    protected UserResponse persistAndPublish(RegisterUserRequest request, String keycloakSub) {
        Instant now = Instant.now();
        UserDocument user = new UserDocument();
        user.setId(UserId.generate().value());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setFullName(request.fullName());
        user.setKeycloakSub(keycloakSub);
        user.setStatus(UserStatus.PENDING_VERIFICATION.name());
        user.setKycLevel(KycLevel.NONE.name());
        user.setMfaEnabled(false);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.insert(user);

        OutboxRecordDocument outbox = new OutboxRecordDocument();
        outbox.setId("OB-" + UlidCreator.getUlid().toString());
        outbox.setAggregateId(user.getId());
        outbox.setTopic(TOPIC);
        outbox.setEventId(UlidCreator.getUlid().toString());
        outbox.setPayload(Map.of(
                "eventId", outbox.getEventId(),
                "eventType", EVENT_TYPE,
                "eventVersion", 1,
                "occurredAt", now.toString(),
                "producedAt", now.toString(),
                "producer", "auth-service@0.1.0",
                "correlationId", String.valueOf(MDC.get("correlationId")),
                "data", Map.of("userId", user.getId(), "registeredAt", now.toString())));
        outbox.setStatus("PENDING");
        outbox.setAttempts(0);
        outbox.setLeaseUntil(Instant.EPOCH);
        outbox.setCreatedAt(now);
        outbox.setExpireAt(now.plus(7, ChronoUnit.DAYS));
        outboxRepository.insert(outbox);

        log.info("user registered userId={} email={}", user.getId(), user.getEmail());

        return new UserResponse(
                user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                UserStatus.valueOf(user.getStatus()),
                KycLevel.valueOf(user.getKycLevel()),
                user.isMfaEnabled(),
                user.getCreatedAt(), user.getUpdatedAt(), user.getVersion());
    }
}
