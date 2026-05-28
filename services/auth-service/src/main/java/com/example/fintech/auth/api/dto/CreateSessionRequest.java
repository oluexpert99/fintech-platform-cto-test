package com.example.fintech.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateSessionRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @Pattern(regexp = "^[0-9]{6}$", message = "INVALID_FORMAT") String otp,
        String deviceLabel
) { }
