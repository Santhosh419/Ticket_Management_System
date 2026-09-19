package com.intellidesk.auth.dto;

import java.time.Instant;

/**
 * Successful register/login result: the stateless JWT, its type, when it
 * expires (so clients can refresh proactively), and the safe user view.
 */
public record AuthResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        UserResponse user
) {}
