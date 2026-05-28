package com.example.fintech.auth.application;

import com.example.fintech.auth.api.dto.CreateSessionRequest;
import com.example.fintech.auth.api.dto.SessionResponse;
import com.example.fintech.auth.domain.exception.InvalidCredentialsException;
import com.example.fintech.auth.domain.exception.MfaInvalidException;
import com.example.fintech.auth.domain.exception.MfaRequiredException;
import com.example.fintech.auth.domain.model.SessionId;
import com.example.fintech.auth.integration.KeycloakAdminClient;
import com.example.fintech.auth.persistence.document.SessionDocument;
import com.example.fintech.auth.persistence.document.UserDocument;
import com.example.fintech.auth.persistence.repository.SessionRepository;
import com.example.fintech.auth.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final KeycloakAdminClient keycloak;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;

    public LoginService(KeycloakAdminClient keycloak,
                        UserRepository userRepository,
                        SessionRepository sessionRepository) {
        this.keycloak = keycloak;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
    }

    public SessionResponse login(CreateSessionRequest request, String userAgent, String remoteIp) {
        if (!keycloak.validateCredentials(request.email(), request.password())) {
            throw new InvalidCredentialsException();
        }

        UserDocument user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (user.isMfaEnabled()) {
            if (request.otp() == null || request.otp().isBlank()) {
                throw new MfaRequiredException(List.of("TOTP"));
            }
            if (!keycloak.verifyTotp(user.getKeycloakSub(), request.otp())) {
                throw new MfaInvalidException(false);
            }
        }

        KeycloakAdminClient.TokenIssueResult tokens = keycloak.issueToken(user.getKeycloakSub(), "accounts:read transactions:write");
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(tokens.refreshExpiresIn());

        SessionDocument session = new SessionDocument();
        session.setId(SessionId.generate().value());
        session.setUserId(user.getId());
        session.setKeycloakSession(tokens.sessionId());
        session.setDeviceLabel(request.deviceLabel() != null ? request.deviceLabel() : deriveDeviceLabel(userAgent));
        session.setIpApprox(anonymise(remoteIp));
        session.setCreatedAt(now);
        session.setLastSeenAt(now);
        session.setExpiresAt(expiresAt);
        sessionRepository.insert(session);

        log.info("session created sessionId={} userId={}", session.getId(), user.getId());

        return new SessionResponse(
                session.getId(), user.getId(),
                tokens.accessToken(), tokens.refreshToken(), "Bearer",
                tokens.expiresIn(), tokens.refreshExpiresIn(), tokens.scope(),
                session.getDeviceLabel(), true,
                session.getCreatedAt(), session.getLastSeenAt());
    }

    private static String deriveDeviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        // Minimal UA parsing — production would use a real ua-parser library.
        String lower = userAgent.toLowerCase();
        if (lower.contains("mobile")) return "Mobile";
        if (lower.contains("safari") && !lower.contains("chrome")) return "Safari";
        if (lower.contains("chrome")) return "Chrome";
        if (lower.contains("firefox")) return "Firefox";
        return "Web";
    }

    /** /24-anonymise for GDPR — the precise IP is never persisted. */
    private static String anonymise(String ip) {
        if (ip == null || ip.isBlank()) return null;
        int lastDot = ip.lastIndexOf('.');
        return lastDot < 0 ? ip : ip.substring(0, lastDot) + ".0/24";
    }
}
