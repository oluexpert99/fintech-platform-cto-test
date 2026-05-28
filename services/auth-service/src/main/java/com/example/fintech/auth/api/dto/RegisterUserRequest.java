package com.example.fintech.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterUserRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 12, max = 256, message = "TOO_SHORT") String password,
        @NotBlank @Size(min = 1, max = 120) String fullName,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "INVALID_FORMAT") String phone
) { }
