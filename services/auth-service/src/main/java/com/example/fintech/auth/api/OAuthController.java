package com.example.fintech.auth.api;

import com.example.fintech.auth.api.dto.TokenResponse;
import com.example.fintech.auth.integration.KeycloakAdminClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/v1/oauth", produces = MediaType.APPLICATION_JSON_VALUE)
public class OAuthController {

    private final KeycloakAdminClient keycloak;

    public OAuthController(KeycloakAdminClient keycloak) {
        this.keycloak = keycloak;
    }

    /**
     * OAuth2 token endpoint — supports only {@code grant_type=refresh_token}. Per api.md §7, the
     * {@code password} grant is not exposed here (use POST /v1/sessions which handles MFA properly).
     */
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public TokenResponse token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "refresh_token", required = false) String refreshToken) {
        if (!"refresh_token".equals(grantType)) {
            throw new UnsupportedOperationException("Only refresh_token grant is supported here");
        }
        KeycloakAdminClient.TokenIssueResult t = keycloak.refreshToken(refreshToken);
        return new TokenResponse(t.accessToken(), t.refreshToken(), "Bearer",
                t.expiresIn(), t.refreshExpiresIn(), t.scope());
    }
}
