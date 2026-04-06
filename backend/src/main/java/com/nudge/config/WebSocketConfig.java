package com.nudge.config;

import com.nudge.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;

/**
 * STOMP over WebSocket configuration.
 *
 * Flow:
 *  1. Frontend connects to /ws with SockJS fallback
 *  2. On CONNECT frame, sends JWT in the Authorization header
 *  3. WebSocketAuthInterceptor validates JWT and sets the Principal
 *  4. Backend pushes notifications to /user/queue/notifications
 *  5. Frontend subscribes to /user/queue/notifications
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtUtil jwtUtil;

    public WebSocketConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple in-memory broker for topics and user queues
        config.enableSimpleBroker("/topic", "/queue");
        // Prefix for @MessageMapping annotated methods
        config.setApplicationDestinationPrefixes("/app");
        // Prefix for user-specific destinations (/user/{username}/queue/...)
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS(); // SockJS fallback for browsers that don't support WebSocket
    }

    /**
     * Authenticate STOMP connections by validating the JWT passed in the
     * Authorization header of the CONNECT frame.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        if (jwtUtil.isValid(token)) {
                            String email = jwtUtil.extractEmail(token);
                            // Set the user principal for this WebSocket session
                            accessor.setUser(new Principal() {
                                @Override public String getName() { return email; }
                            });
                            log.debug("WebSocket authenticated: {}", email);
                        }
                    }
                }
                return message;
            }
        });
    }
}
