package com.example.fintech.auth.application;

import com.example.fintech.auth.api.dto.PagedResponse;
import com.example.fintech.auth.api.dto.SessionResponse;
import com.example.fintech.auth.domain.model.UserId;
import com.example.fintech.auth.integration.KeycloakAdminClient;
import com.example.fintech.auth.persistence.document.SessionDocument;
import com.example.fintech.auth.persistence.repository.SessionRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final KeycloakAdminClient keycloak;

    public SessionService(SessionRepository sessionRepository, KeycloakAdminClient keycloak) {
        this.sessionRepository = sessionRepository;
        this.keycloak = keycloak;
    }

    public PagedResponse<SessionResponse> list(UserId caller, String currentSessionState, int limit) {
        List<SessionDocument> rows = sessionRepository.findByUserIdOrderByLastSeenAtDesc(caller.value(), Limit.of(limit));
        List<SessionResponse> data = rows.stream()
                .map(s -> new SessionResponse(
                        s.getId(), s.getUserId(),
                        null, null, null, 0, 0, null,  // tokens are not returned on list
                        s.getDeviceLabel(),
                        s.getKeycloakSession() != null && s.getKeycloakSession().equals(currentSessionState),
                        s.getCreatedAt(), s.getLastSeenAt()))
                .toList();
        return PagedResponse.of(data, null, false, limit);
    }

    public void revokeCurrent(String currentSessionState) {
        if (currentSessionState == null || currentSessionState.isBlank()) return;
        Optional<SessionDocument> session = sessionRepository.findByKeycloakSession(currentSessionState);
        session.ifPresent(s -> {
            sessionRepository.deleteById(s.getId());
            try { keycloak.revokeSession(currentSessionState); } catch (Exception ignored) { /* best-effort */ }
        });
    }

    public void revoke(UserId caller, String sessionId) {
        Optional<SessionDocument> session = sessionRepository.findById(sessionId);
        session.filter(s -> caller.value().equals(s.getUserId())).ifPresent(s -> {
            sessionRepository.deleteById(s.getId());
            try { keycloak.revokeSession(s.getKeycloakSession()); } catch (Exception ignored) { /* best-effort */ }
        });
    }
}
