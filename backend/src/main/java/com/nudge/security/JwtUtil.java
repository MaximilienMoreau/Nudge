package com.nudge.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

/**
 * Utility for creating and validating JWT tokens.
 *
 * P2: The signing key is derived once at startup (@PostConstruct) and cached
 *     rather than re-decoded on every request.
 *
 * S6: Tokens include a "tv" (tokenVersion) claim. The filter compares it
 *     against the User.tokenVersion stored in the DB; a mismatch rejects
 *     the token — effectively revoking all tokens issued before the version
 *     was incremented (e.g. on password change or explicit logout).
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationMs;

    /** P2: Cached signing key — derived once at startup. */
    private Key signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** Generate a signed JWT for the given user, embedding the tokenVersion. */
    public String generateToken(Long userId, String email, int tokenVersion) {
        return Jwts.builder()
                .setSubject(email)
                .claim("userId", userId)
                .claim("tv", tokenVersion)         // S6: token version
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Parse and validate a token, returning its claims. Throws on invalid token. */
    public Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public String extractEmail(String token) {
        return extractClaims(token).getSubject();
    }

    public Long extractUserId(String token) {
        return extractClaims(token).get("userId", Long.class);
    }

    /** S6: Extract the tokenVersion embedded in the JWT. */
    public int extractTokenVersion(String token) {
        Object tv = extractClaims(token).get("tv");
        if (tv == null) return 0;
        return ((Number) tv).intValue();
    }

    /** Returns true if the token signature is valid and it has not expired. */
    public boolean isValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }
}
