package com.upi.reconcile.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration — stateless JWT-based authentication.
 * <p>
 * Public paths:
 * <ul>
 *   <li>POST /api/auth/signup and POST /api/auth/login</li>
 *   <li>/actuator/prometheus and /actuator/health</li>
 *   <li>Gateway webhook endpoints (called by Razorpay/PayU/Cashfree directly)</li>
 *   <li>WebSocket handshake endpoints (JWT checked at STOMP level)</li>
 * </ul>
 * <p>
 * All other /api/ endpoints require a valid JWT in the
 * Authorization: Bearer header.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final MerchantUserDetailsService merchantUserDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — stateless API, no server-side sessions
            .csrf(csrf -> csrf.disable())

            // Stateless session management
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                    // Auth endpoints are public
                    .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()

                    // Actuator endpoints are public
                    .requestMatchers("/actuator/**").permitAll()

                    // Gateway webhook endpoints are public (called by Razorpay/PayU/Cashfree)
                    .requestMatchers("/api/connectors/*/webhook").permitAll()

                    // Generic webhook endpoint is public
                    .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()

                    // WebSocket handshake is public (JWT checked at STOMP level)
                    .requestMatchers("/ws/**").permitAll()

                    // Everything else under /api/** requires authentication
                    .requestMatchers("/api/**").authenticated()

                    // Non-API requests (static resources, etc.) are permitted
                    .anyRequest().permitAll()
            )

            // Register JWT filter before UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

            // Return 401 for unauthenticated requests instead of default 403
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Authentication required\"}");
                    })
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(merchantUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}
