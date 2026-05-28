package com.example.fintech.auth.api;

import com.example.fintech.auth.api.dto.CreateSessionRequest;
import com.example.fintech.auth.api.dto.PagedResponse;
import com.example.fintech.auth.api.dto.SessionResponse;
import com.example.fintech.auth.application.LoginService;
import com.example.fintech.auth.application.SessionService;
import com.example.fintech.auth.domain.model.UserId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(path = "/v1/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class SessionsController {

    private final LoginService loginService;
    private final SessionService sessionService;

    public SessionsController(LoginService loginService, SessionService sessionService) {
        this.loginService = loginService;
        this.sessionService = sessionService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> login(
            @Valid @RequestBody CreateSessionRequest request,
            HttpServletRequest httpRequest) {
        SessionResponse response = loginService.login(
                request,
                httpRequest.getHeader("User-Agent"),
                httpRequest.getRemoteAddr());
        return ResponseEntity.created(URI.create("/v1/sessions/" + response.sessionId())).body(response);
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        String sessionState = jwt.getClaimAsString("session_state");
        sessionService.revokeCurrent(sessionState);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public PagedResponse<SessionResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "limit", defaultValue = "25") int limit) {
        return sessionService.list(
                UserId.of(jwt.getSubject()),
                jwt.getClaimAsString("session_state"),
                Math.min(Math.max(limit, 1), 100));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        sessionService.revoke(UserId.of(jwt.getSubject()), sessionId);
        return ResponseEntity.noContent().build();
    }
}
