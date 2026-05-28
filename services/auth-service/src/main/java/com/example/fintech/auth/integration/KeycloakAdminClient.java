package com.example.fintech.auth.integration;

/**
 * Outbound port to Keycloak's admin REST API.
 *
 * <p>The real implementation talks to Keycloak via {@code WebClient} (see
 * {@link KeycloakWebClient}). Method shapes intentionally hide Keycloak's specific representation
 * types so the service layer never imports the Keycloak SDK directly.
 *
 * <p>Areas marked TODO require a running Keycloak to develop against and are stubbed for the
 * test submission — they should be implemented before this service is shipped.
 */
public interface KeycloakAdminClient {

    /** Create a user in Keycloak. Returns the new user's {@code sub} claim value. */
    String createUser(String email, String password, String fullName, String phone);

    /**
     * Verify the user's credentials against Keycloak. Does NOT issue a token — call
     * {@link #issueToken} after MFA is satisfied. Returns true on success.
     */
    boolean validateCredentials(String email, String password);

    /** Whether the user has MFA enrolled. */
    boolean hasMfaEnrolled(String keycloakSub);

    /** Verify a TOTP OTP. */
    boolean verifyTotp(String keycloakSub, String otp);

    /**
     * Issue an access/refresh token pair for the user. Wraps Keycloak's token endpoint.
     * In production this uses token exchange so the gateway sees a real OIDC token.
     */
    TokenIssueResult issueToken(String keycloakSub, String scope);

    /** Rotate a refresh token. Returns new token pair; old refresh token is invalidated. */
    TokenIssueResult refreshToken(String refreshToken);

    /** Revoke a specific session at Keycloak. */
    void revokeSession(String keycloakSessionId);

    /** Revoke every session of a user at Keycloak. */
    void revokeAllSessions(String keycloakSub);

    /** Result of a Keycloak token-issuance call. */
    record TokenIssueResult(
            String accessToken,
            String refreshToken,
            long expiresIn,
            long refreshExpiresIn,
            String scope,
            String sessionId) { }
}
