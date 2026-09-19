package com.intellidesk.auth;

import com.intellidesk.user.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Single owner of JWT creation and verification (HMAC signing; the concrete
 * SHA variant is chosen by jjwt from the key size - 256-bit key -> HS256).
 *
 * <p>Design points:</p>
 * <ul>
 *   <li>The HMAC key is derived once from the externalized secret; HS256
 *       requires at least 256 bits (32 bytes) - shorter secrets are refused
 *       at startup instead of failing in mysterious ways at runtime.</li>
 *   <li>Token carries subject (email), the role and the user id as claims.
 *       The filter re-loads the user from the database on every request, so a
 *       revoked/deactivated account loses access immediately even with a
 *       valid token; claims exist for observability, never for authorization
 *       decisions on their own (never trust stale claims).</li>
 *   <li>{@link JwtException} is the contract for "this token cannot be
 *       trusted" - the filter translates it into a plain 401.</li>
 * </ul>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Minimum length for HS256: 256 bits = 32 bytes. */
    static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final Duration tokenTtl;

    public JwtService(JwtProperties properties) {
        byte[] secretBytes = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "intellidesk.security.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes (256 bits for HS256). Set the INTELLIDESK_JWT_SECRET environment variable.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.tokenTtl = Duration.ofMinutes(properties.expirationMinutes());
        log.info("JWT service initialized (HMAC-SHA, key {} bits, token TTL {} minutes)",
                secretBytes.length * 8, properties.expirationMinutes());
    }

    /** A freshly minted token plus the instant it stops being valid. */
    public record IssuedToken(String value, Instant expiresAt) {}

    public IssuedToken issue(String email, Role role, Long userId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(tokenTtl);
        String token = Jwts.builder()
                .subject(email)
                .claim("role", role.name())
                .claim("uid", userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
        return new IssuedToken(token, expiry);
    }

    /**
     * Verifies signature and expiry, returning the claims.
     *
     * @throws JwtException if the token is malformed, tampered with or expired
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
