package com.intellidesk.auth;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, externalized JWT configuration - no secret literal exists anywhere
 * in source code.
 *
 * <ul>
 *   <li>Production/default profile: {@code INTELLIDESK_JWT_SECRET} env var is
 *       required; an empty value makes {@link JwtService} fail fast with a
 *       clear operator message.</li>
 *   <li>The {@code h2} demo profile carries an explicit throwaway secret so
 *       the zero-setup demo still runs.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "intellidesk.security.jwt")
public record JwtProperties(
        String secret,
        @Positive(message = "expiration-minutes must be positive")
        int expirationMinutes
) {}
