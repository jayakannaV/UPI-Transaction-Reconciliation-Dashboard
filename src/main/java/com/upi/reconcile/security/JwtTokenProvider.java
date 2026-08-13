package com.upi.reconcile.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Stateless JWT token provider — signs, parses, and validates HS256 tokens.
 * <p>
 * Token claims:
 * <ul>
 *   <li>{@code sub} — merchant UUID</li>
 *   <li>{@code email} — merchant email</li>
 *   <li>{@code iat} / {@code exp} — standard timestamps</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtTokenProvider(JwtProperties props) {
        byte[] keyBytes = Base64.getDecoder().decode(props.getSecret());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = props.getExpirationMs();
    }

    /**
     * Generate a signed JWT for a merchant.
     */
    public String generateToken(UUID merchantId, String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(merchantId.toString())
                .claim("email", email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parse and validate a JWT, returning the claims.
     *
     * @throws JwtException if the token is invalid or expired
     */
    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extract the merchant UUID from the token's {@code sub} claim.
     */
    public UUID getMerchantId(String token) {
        Claims claims = validateToken(token);
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Check whether a token string is structurally valid and not expired.
     */
    public boolean isValid(String token) {
        try {
            validateToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }
}
