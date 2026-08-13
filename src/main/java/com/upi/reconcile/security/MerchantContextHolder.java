package com.upi.reconcile.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Utility to extract the authenticated merchant's UUID from the
 * {@link SecurityContextHolder}.
 * <p>
 * The JWT filter sets the principal to the merchant's UUID, so this
 * helper provides a typed accessor for controllers and services.
 */
public final class MerchantContextHolder {

    private MerchantContextHolder() {
        // utility class
    }

    /**
     * Returns the current authenticated merchant's UUID.
     *
     * @throws IllegalStateException if no authenticated merchant is present
     */
    public static UUID currentMerchantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("No authenticated merchant in SecurityContext");
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID uuid) {
            return uuid;
        }
        // Fallback: principal might be a string representation
        return UUID.fromString(principal.toString());
    }
}
