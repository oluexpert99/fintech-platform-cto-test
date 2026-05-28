package com.example.fintech.auth.api;

import com.example.fintech.auth.api.dto.RegisterUserRequest;
import com.example.fintech.auth.api.dto.UserResponse;
import com.example.fintech.auth.application.RegisterUserService;
import com.example.fintech.auth.domain.exception.MissingIdempotencyKeyException;
import com.example.fintech.auth.domain.exception.UserNotFoundException;
import com.example.fintech.auth.domain.model.UserId;
import com.example.fintech.auth.domain.model.KycLevel;
import com.example.fintech.auth.domain.model.UserStatus;
import com.example.fintech.auth.persistence.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(path = "/v1/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class UsersController {

    private final RegisterUserService registerUserService;
    private final UserRepository userRepository;

    public UsersController(RegisterUserService registerUserService, UserRepository userRepository) {
        this.registerUserService = registerUserService;
        this.userRepository = userRepository;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> register(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RegisterUserRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
        UserResponse response = registerUserService.register(request);
        return ResponseEntity.created(URI.create("/v1/users/" + response.userId())).body(response);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userRepository.findByKeycloakSub(jwt.getSubject())
                .map(u -> new UserResponse(
                        u.getId(), u.getEmail(), u.getFullName(), u.getPhone(),
                        UserStatus.valueOf(u.getStatus()),
                        KycLevel.valueOf(u.getKycLevel()),
                        u.isMfaEnabled(),
                        u.getCreatedAt(), u.getUpdatedAt(), u.getVersion()))
                .orElseThrow(() -> new UserNotFoundException(UserId.of("U-UNKNOWN")));
    }
}
