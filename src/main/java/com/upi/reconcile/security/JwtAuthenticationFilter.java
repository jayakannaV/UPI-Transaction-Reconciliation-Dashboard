package com.upi.reconcile.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Extracts and validates a JWT from the {@code Authorization: Bearer <token>}
 * header (or {@code ?token=<jwt>} query param for WebSocket handshakes).
 * <p>
 * On success, sets a {@link UsernamePasswordAuthenticationToken} with the
 * merchant's UUID as principal, enabling downstream extraction via
 * {@link MerchantContextHolder}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);

        if (token != null && jwtTokenProvider.isValid(token)) {
            try {
                Claims claims = jwtTokenProvider.validateToken(token);
                UUID merchantId = UUID.fromString(claims.getSubject());
                String email = claims.get("email", String.class);

                var auth = new UsernamePasswordAuthenticationToken(
                        merchantId,              // principal = merchant UUID
                        null,                    // credentials (not needed after validation)
                        List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));

                // Store email as authentication detail for convenience
                auth.setDetails(email);

                SecurityContextHolder.getContext().setAuthentication(auth);

                log.debug("JWT authenticated merchant={} email={}", merchantId, email);
            } catch (Exception e) {
                log.debug("JWT validation failed: {}", e.getMessage());
                // Do not set authentication — request continues as anonymous
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Tries to resolve a JWT from the Authorization header first,
     * then falls back to a {@code token} query parameter (for WebSocket).
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // Fallback: query param for WebSocket handshake
        String queryToken = request.getParameter("token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        return null;
    }
}
