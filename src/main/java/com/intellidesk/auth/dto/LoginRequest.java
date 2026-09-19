package com.intellidesk.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Login request. Validation stays loose (no password pattern/size) - any
 * malformed attempt is simply wrong credentials; strict validation here
 * would leak registration rules to attackers probing the login endpoint.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {}
