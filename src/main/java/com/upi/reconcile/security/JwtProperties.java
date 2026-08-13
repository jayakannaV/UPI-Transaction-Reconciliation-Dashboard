package com.upi.reconcile.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT configuration properties bound from {@code app.jwt.*} in
 * {@code application.yml}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /**
     * Base64-encoded 256-bit secret for HS256 signing.
     * Override via {@code APP_JWT_SECRET} env var in production.
     */
    private String secret;

    /** Token expiration in milliseconds (default 24 hours). */
    private long expirationMs = 86_400_000L;
}
