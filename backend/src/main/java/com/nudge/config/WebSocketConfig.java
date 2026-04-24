package com.nudge.config;

import com.nudge.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.Cookie;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;

/**
 * STOMP over WebSocket configuration.
 *
 * S3: Authentication at two levels:
 *   1. HTTP handshake level: HandshakeInterceptor checks a ?token= query param
 *      before the WebSocket upgrade is granted.
 *   2. STOMP CONNECT level: ChannelInterceptor validates the JWT in the
 *      Authorization header of the STOMP CONNECT frame and sets the Principal.
 *
 * Frontend must connect with:
 *   new SockJS(WS_URL + '?token=' + jwtToken)
 *
 * A4 note: For production, replace enableSimpleBroker with enableStompBrokerRelay
 *   pointed at a RabbitMQ / ActiveMQ instance so notifications survive restarts.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtUtil jwtUtil;

    @Value("${app.cors.allowed-origins:*}")
    private String corsAllowedOrigins;

    public WebSocketConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(corsAllowedOrigins.split(","))
                // S3: Validate the ?token= query param at HTTP handshake time
                .addInterceptors(new JwtHandshakeInterceptor(jwtUtil))
                .withSockJS();
    }

    /**
     * S3 STOMP-level: validate JWT in the CONNECT frame header and set Principal.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    // Try Authorization header (extension / non-cookie clients)
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        if (jwtUtil.isValid(token)) {
                            String email = jwtUtil.extractEmail(token);
                            accessor.setUser(() -> email);
                            log.debug("WebSocket STOMP authenticated via Bearer: {}", email);
                        } else {
                            log.warn("WebSocket STOMP CONNECT rejected: invalid Bearer token");
                        }
                    } else {
                        // Cookie-based auth: user was set during HTTP handshake
                        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
                        if (sessionAttrs != null && sessionAttrs.containsKey("wsUser")) {
                            String email = (String) sessionAttrs.get("wsUser");
                            accessor.setUser(() -> email);
                            log.debug("WebSocket STOMP authenticated via cookie: {}", email);
                        }
                    }
                }
                return message;
            }
        });
    }

    // ── S3: HTTP-level handshake interceptor ───────────────────

    private static class JwtHandshakeInterceptor implements HandshakeInterceptor {

        private final JwtUtil jwtUtil;

        JwtHandshakeInterceptor(JwtUtil jwtUtil) { this.jwtUtil = jwtUtil; }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            // 1. Try httpOnly cookie (web frontend)
            if (request instanceof ServletServerHttpRequest servletRequest) {
                Cookie[] cookies = servletRequest.getServletRequest().getCookies();
                if (cookies != null) {
                    for (Cookie cookie : cookies) {
                        if ("nudge_jwt".equals(cookie.getName()) && jwtUtil.isValid(cookie.getValue())) {
                            attributes.put("wsUser", jwtUtil.extractEmail(cookie.getValue()));
                            return true;
                        }
                    }
                }
            }
            // 2. Fall back to ?token= query param (extension / non-cookie clients)
            String query = request.getURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("token=")) {
                        String token = param.substring("token=".length());
                        if (jwtUtil.isValid(token)) {
                            attributes.put("wsUser", jwtUtil.extractEmail(token));
                            return true;
                        }
                    }
                }
            }
            log.warn("WebSocket handshake rejected: no valid cookie or ?token= param");
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                   WebSocketHandler handler, Exception ex) {}
    }
}
