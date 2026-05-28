package com.example.fintech.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PatchUserRequest(
        @Size(min = 1, max = 120) String fullName
) { }
