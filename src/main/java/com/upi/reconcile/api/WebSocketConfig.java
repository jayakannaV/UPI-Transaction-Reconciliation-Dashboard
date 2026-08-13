package com.upi.reconcile.api;

import com.upi.reconcile.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;
import java.util.UUID;

/**
 * STOMP-over-SockJS WebSocket configuration — ARCHITECTURE.md §7.
 * <p>
 * Registers {@code /ws/live-feed} as a SockJS-enabled STOMP endpoint.
 * The simple in-memory broker relays messages on {@code /topic/*} destinations.
 * <p>
 * JWT authentication is performed on STOMP CONNECT by extracting the token
 * from the {@code Authorization} native header or from the {@code token}
 * native header and validating it.
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable a simple in-memory broker for /topic destinations
        registry.enableSimpleBroker("/topic");
        // Application-bound messages use /app prefix
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/live-feed")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String token = resolveTokenFromStomp(accessor);
                    if (token != null && jwtTokenProvider.isValid(token)) {
                        try {
                            Claims claims = jwtTokenProvider.validateToken(token);
                            UUID merchantId = UUID.fromString(claims.getSubject());

                            var auth = new UsernamePasswordAuthenticationToken(
                                    merchantId, null,
                                    List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));

                            accessor.setUser(auth);
                            log.debug("WebSocket STOMP CONNECT authenticated merchant={}", merchantId);
                        } catch (Exception e) {
                            log.warn("WebSocket JWT validation failed: {}", e.getMessage());
                        }
                    }
                }

                return message;
            }
        });
    }

    /**
     * Resolves the JWT from STOMP native headers:
     * 1. {@code Authorization: Bearer <token>}
     * 2. {@code token: <jwt>}
     */
    private String resolveTokenFromStomp(StompHeaderAccessor accessor) {
        // Try Authorization header first
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // Fallback: token header
        String tokenHeader = accessor.getFirstNativeHeader("token");
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            return tokenHeader;
        }
        return null;
    }
}
