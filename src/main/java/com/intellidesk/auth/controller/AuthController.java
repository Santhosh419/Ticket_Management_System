package com.intellidesk.auth.controller;

import com.intellidesk.auth.dto.AuthResponse;
import com.intellidesk.auth.dto.LoginRequest;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.auth.dto.UserResponse;
import com.intellidesk.auth.service.AuthService;
import com.intellidesk.security.IntelliDeskUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Public authentication endpoints plus the identity probe /api/auth/me.
 *
 * OpenAPI annotations exist so the Swagger UI documents the contract -
 * they carry no runtime behavior.
 */
@Tag(name = "Authentication", description = "Registration, login and current-user identity")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Register a new customer account and receive a JWT")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created; JWT returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "409", description = "Email already registered")
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity
                .created(URI.create("/api/auth/me"))
                .body(response);
    }

    @Operation(summary = "Authenticate with email/password and receive a JWT")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated; JWT returned"),
            @ApiResponse(responseCode = "400", description = "Malformed request"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials or deactivated account")
    })
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Return the identity of the authenticated caller")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token")
    })
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal IntelliDeskUserDetails principal) {
        return UserResponse.from(principal.getUser());
    }
}
