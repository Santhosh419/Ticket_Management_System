package com.intellidesk.auth;

import com.intellidesk.user.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests - no Spring context. Covers the four properties a token
 * service must have: correct claims, expiry enforcement, signature
 * enforcement, and fail-fast on weak secrets.
 */
class JwtServiceTest {

    private static final String SECRET =
            "unit-test-secret-value-for-jwt-service-0123456789abcdef";

    private final JwtService jwtService = new JwtService(
            new JwtProperties(SECRET, 60));

    @Test
    void issuedTokenCarriesSubjectRoleUidAndExpiry() {
        JwtService.IssuedToken issued = jwtService.issue("user@test.local", Role.AGENT, 42L);

        assertThat(issued.value()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(Instant.now());

        Claims claims = jwtService.parseToken(issued.value());
        assertThat(claims.getSubject()).isEqualTo("user@test.local");
        assertThat(claims.get("role", String.class)).isEqualTo("AGENT");
        assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
        assertThat(claims.getExpiration().toInstant()).isCloseTo(issued.expiresAt(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expiredService = new JwtService(new JwtProperties(SECRET, -1));
        JwtService.IssuedToken issued = expiredService.issue("user@test.local", Role.CUSTOMER, 1L);

        assertThatThrownBy(() -> expiredService.parseToken(issued.value()))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtService otherKeyService = new JwtService(
                new JwtProperties("a-completely-different-secret-value-0123456789abcdef", 60));
        JwtService.IssuedToken issued = otherKeyService.issue("user@test.local", Role.CUSTOMER, 1L);

        assertThatThrownBy(() -> jwtService.parseToken(issued.value()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        JwtService.IssuedToken issued = jwtService.issue("user@test.local", Role.CUSTOMER, 1L);
        String[] parts = issued.value().split("\\.");
        // Flip the payload: signature no longer matches
        String tampered = parts[0] + ".eyJzdWIiOiJldmlsIn0." + parts[2];

        assertThatThrownBy(() -> jwtService.parseToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretFailsFastAtStartup() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", 60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }
}
