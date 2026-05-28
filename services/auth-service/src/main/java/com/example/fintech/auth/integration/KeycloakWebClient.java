package com.example.fintech.auth.integration;

import com.example.fintech.auth.domain.exception.AccountLockedException;
import com.example.fintech.auth.domain.exception.EmailAlreadyRegisteredException;
import com.example.fintech.auth.domain.exception.RefreshTokenRevokedException;
import com.example.fintech.auth.domain.exception.WeakPasswordException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link KeycloakAdminClient} that talks to Keycloak via {@link WebClient}.
 *
 * <p>Wire calls are kept synchronous via {@code .block()} for the test deliverable — the
 * surrounding service code is non-reactive. In production we'd be reactive end-to-end or
 * front this with a dedicated thread pool.
 *
 * <p>TODO(spec §3.3): the user-creation, MFA-list, MFA-verify methods make Keycloak admin REST
 * calls that require a service-account token (client_credentials). The token-cache logic is
 * stubbed; expect to wire it before production. The MFA endpoints in particular need Keycloak's
 * Required-Actions / Credentials APIs which are non-trivial.
 */
@Component
public class KeycloakWebClient implements KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakWebClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final String realm;
    private final String adminClientId;
    private final String adminClientSecret;
    private volatile String cachedAdminToken;

    /**
     * Real Keycloak tokens minted during {@link #validateCredentials} and handed to the very next
     * {@link #issueToken} call, keyed by the user's {@code sub}. Bounded to one entry per user: a
     * re-login overwrites any stale entry, and {@code issueToken} removes it on read.
     */
    private final Map<String, TokenIssueResult> pendingTokens = new ConcurrentHashMap<>();

    public KeycloakWebClient(
            WebClient.Builder webClientBuilder,
            @Value("${keycloak.server-url:http://keycloak:8080}") String serverUrl,
            @Value("${keycloak.realm:fintech}") String realm,
            @Value("${keycloak.admin-client-id:auth-service}") String adminClientId,
            @Value("${keycloak.admin-client-secret:dev-only-secret}") String adminClientSecret) {
        this.webClient = webClientBuilder.baseUrl(serverUrl).build();
        this.realm = realm;
        this.adminClientId = adminClientId;
        this.adminClientSecret = adminClientSecret;
    }

    @Override
    public String createUser(String email, String password, String fullName, String phone) {
        // NOTE: `phone` is intentionally NOT stored on the Keycloak user. The realm's declarative
        // user profile only manages username/email/firstName/lastName (unmanaged attrs disabled),
        // so an extra `phone` attribute makes the profile "incomplete" and trips VERIFY_PROFILE at
        // login ("Account is not fully set up"). Phone is persisted in our own Mongo user record
        // (RegisterUserService), which is the system of record for it.
        Map<String, Object> body = Map.of(
                "username", email,
                "email", email,
                "enabled", true,
                // Dev convenience: mark the email verified so newly-registered users can also log
                // in through Keycloak's browser flow (account console). Email-ownership proof is
                // out of scope for this build; the API login path (direct grant) never required it.
                "emailVerified", true,
                "firstName", firstName(fullName),
                "lastName", lastName(fullName),
                "credentials", new Object[]{Map.of("type", "password", "value", password, "temporary", false)});

        try {
            String location = webClient.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .header("Authorization", "Bearer " + getAdminToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .map(r -> r.getHeaders().getFirst("Location"))
                    .block();
            if (location == null) {
                throw new IllegalStateException("Keycloak did not return a Location header");
            }
            return location.substring(location.lastIndexOf('/') + 1);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new EmailAlreadyRegisteredException(email);
            }
            if (e.getStatusCode().value() == 400 && e.getResponseBodyAsString().toLowerCase().contains("password")) {
                throw new WeakPasswordException("Rejected by Keycloak password policy");
            }
            throw e;
        }
    }

    @Override
    public boolean validateCredentials(String email, String password) {
        // Spec §4.2 — resource-owner password grant via the realm's `auth-service` confidential
        // client. The grant both validates the credentials AND yields real Keycloak tokens; we keep
        // them (keyed by the user's sub) so the following issueToken() returns them instead of a
        // stub. Returns true on 200, false on 401, throws AccountLockedException on 423.
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", adminClientId);
        form.add("client_secret", adminClientSecret);
        form.add("username", email);
        form.add("password", password);
        // No explicit scope: the auth-service client's default scopes already grant the
        // accounts:* / transactions:* permissions a user session needs (see realm-export.json).
        try {
            Map<?, ?> body = webClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            if (body == null) return false;
            String accessToken = asString(body.get("access_token"));
            TokenIssueResult tokens = new TokenIssueResult(
                    accessToken,
                    asString(body.get("refresh_token")),
                    asLong(body.get("expires_in")),
                    asLong(body.get("refresh_expires_in")),
                    asString(body.get("scope")),
                    asString(body.get("session_state")));
            pendingTokens.put(subjectOf(accessToken), tokens);
            return true;
        } catch (WebClientResponseException e) {
            HttpStatusCode s = e.getStatusCode();
            if (s.value() == 423) throw new AccountLockedException(60L);
            return false; // 401 invalid_grant
        }
    }

    @Override
    public boolean hasMfaEnrolled(String keycloakSub) {
        // TODO(spec §3.3): query Keycloak's credentials endpoint and check for TOTP factors.
        return false;
    }

    @Override
    public boolean verifyTotp(String keycloakSub, String otp) {
        // TODO(spec §3.3): use Keycloak's required-actions or a dedicated verification endpoint.
        return false;
    }

    @Override
    public TokenIssueResult issueToken(String keycloakSub, String scope) {
        // Return the real tokens minted by the immediately-preceding validateCredentials() call.
        // The `scope` argument is advisory; the authoritative scope is whatever Keycloak granted.
        TokenIssueResult tokens = pendingTokens.remove(keycloakSub);
        if (tokens == null) {
            throw new IllegalStateException(
                    "No pending Keycloak token for subject " + keycloakSub
                            + "; validateCredentials() must run immediately before issueToken()");
        }
        return tokens;
    }

    @Override
    public TokenIssueResult refreshToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", adminClientId);
        form.add("client_secret", adminClientSecret);
        form.add("refresh_token", refreshToken);
        try {
            Map<?, ?> body = webClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            if (body == null) throw new RefreshTokenRevokedException();
            return new TokenIssueResult(
                    asString(body.get("access_token")),
                    asString(body.get("refresh_token")),
                    asLong(body.get("expires_in")),
                    asLong(body.get("refresh_expires_in")),
                    asString(body.get("scope")),
                    asString(body.get("session_state")));
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 401) {
                throw new RefreshTokenRevokedException();
            }
            throw e;
        }
    }

    @Override
    public void revokeSession(String keycloakSessionId) {
        try {
            webClient.delete()
                    .uri("/admin/realms/{realm}/sessions/{id}", realm, keycloakSessionId)
                    .header("Authorization", "Bearer " + getAdminToken())
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
        } catch (Exception e) {
            log.warn("Keycloak session revoke failed (best-effort): {}", e.getMessage());
        }
    }

    @Override
    public void revokeAllSessions(String keycloakSub) {
        try {
            webClient.post()
                    .uri("/admin/realms/{realm}/users/{id}/logout", realm, keycloakSub)
                    .header("Authorization", "Bearer " + getAdminToken())
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
        } catch (Exception e) {
            log.warn("Keycloak revoke-all-sessions failed: {}", e.getMessage());
        }
    }

    /**
     * Service-account token cache. The token is short-lived; we re-obtain on demand and don't
     * eagerly refresh. A small race may issue two tokens for the same instance — harmless.
     *
     * <p>TODO(spec §6.3): proactive renewal at ~80% of token lifetime; metrics on renewals.
     */
    private String getAdminToken() {
        if (cachedAdminToken != null) return cachedAdminToken;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", adminClientId);
        form.add("client_secret", adminClientSecret);
        Map<?, ?> body = webClient.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(TIMEOUT)
                .block();
        cachedAdminToken = body == null ? null : asString(body.get("access_token"));
        return cachedAdminToken;
    }

    private static String firstName(String fullName) {
        if (fullName == null) return "";
        int sp = fullName.indexOf(' ');
        return sp < 0 ? fullName : fullName.substring(0, sp);
    }

    private static String lastName(String fullName) {
        if (fullName == null) return "";
        int sp = fullName.lastIndexOf(' ');
        return sp < 0 ? "" : fullName.substring(sp + 1);
    }

    /** Extract the {@code sub} claim from a JWT access token without verifying its signature. */
    private static String subjectOf(String jwt) {
        if (jwt == null) throw new IllegalStateException("Keycloak returned no access_token");
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new IllegalStateException("Malformed JWT from Keycloak");
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            return MAPPER.readTree(payload).path("sub").asText();
        } catch (IllegalArgumentException | IOException e) {
            throw new IllegalStateException("Cannot parse JWT payload from Keycloak", e);
        }
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    private static long asLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
