package com.nudge.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Key;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JwtUtil (generation, validation, claim extraction, tokenVersion).
 * No Spring context needed — fields are set directly and init() is invoked manually.
 */
class JwtUtilTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "a-256-bit-secret-key-for-jwt-testing-only".getBytes());

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 3_600_000L);
        ReflectionTestUtils.invokeMethod(jwtUtil, "init");
    }

    @Test
    void generateToken_thenExtractClaims_roundTrips() {
        String token = jwtUtil.generateToken(42L, "user@example.com", 3);

        assertThat(jwtUtil.extractEmail(token)).isEqualTo("user@example.com");
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(42L);
        assertThat(jwtUtil.extractTokenVersion(token)).isEqualTo(3);
    }

    @Test
    void isValid_returnsTrue_forFreshToken() {
        String token = jwtUtil.generateToken(1L, "user@example.com", 0);
        assertThat(jwtUtil.isValid(token)).isTrue();
    }

    @Test
    void isValid_returnsFalse_forExpiredToken() {
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", -1000L);
        String expired = jwtUtil.generateToken(1L, "user@example.com", 0);

        assertThat(jwtUtil.isValid(expired)).isFalse();
    }

    @Test
    void isValid_returnsFalse_forGarbageToken() {
        assertThat(jwtUtil.isValid("not-a-jwt")).isFalse();
    }

    @Test
    void isValid_returnsFalse_forTamperedSignature() {
        String token = jwtUtil.generateToken(1L, "user@example.com", 0);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(jwtUtil.isValid(tampered)).isFalse();
    }

    @Test
    void extractClaims_throws_forTokenSignedWithDifferentKey() {
        JwtUtil otherKeyUtil = new JwtUtil();
        ReflectionTestUtils.setField(otherKeyUtil, "secret",
                Base64.getEncoder().encodeToString("a-completely-different-256-bit-secret-key".getBytes()));
        ReflectionTestUtils.setField(otherKeyUtil, "expirationMs", 3_600_000L);
        ReflectionTestUtils.invokeMethod(otherKeyUtil, "init");

        String tokenFromOtherKey = otherKeyUtil.generateToken(1L, "user@example.com", 0);

        assertThatThrownBy(() -> jwtUtil.extractClaims(tokenFromOtherKey))
            .isInstanceOf(SignatureException.class);
    }

    @Test
    void extractTokenVersion_returnsZero_whenClaimMissing() {
        // Simulate a legacy token minted before the tokenVersion claim existed by
        // building one directly with the same signing key, skipping the claim.
        String legacyToken = Jwts.builder()
                .setSubject("legacy@example.com")
                .claim("userId", 1L)
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith((Key) ReflectionTestUtils.getField(jwtUtil, "signingKey"), SignatureAlgorithm.HS256)
                .compact();

        assertThat(jwtUtil.extractTokenVersion(legacyToken)).isEqualTo(0);
    }
}
