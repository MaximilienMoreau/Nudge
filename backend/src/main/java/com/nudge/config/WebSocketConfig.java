package com.nudge.config;

import com.nudge.repository.UserRepository;
import com.nudge.security.JwtUtil;
import com.nudge.security.SecurityConstants;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cookie/Bearer authentication with database revocation checks in both directions. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtUtil jwtUtil;
    private final UserRepository users;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    public WebSocketConfig(JwtUtil jwtUtil, UserRepository users) {
        this.jwtUtil = jwtUtil;
        this.users = users;
    }

    boolean isCurrent(String token) {
        if (token == null || !jwtUtil.isValid(token)) return false;
        return users.findByEmail(jwtUtil.extractEmail(token))
                .filter(user -> user.getId().equals(jwtUtil.extractUserId(token)))
                .filter(user -> user.getTokenVersion() == jwtUtil.extractTokenVersion(token))
                .isPresent();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toArray(String[]::new))
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                            WebSocketHandler handler, Map<String, Object> attributes) {
                        String token = null;
                        if (request instanceof ServletServerHttpRequest servlet) {
                            Cookie[] cookies = servlet.getServletRequest().getCookies();
                            if (cookies != null) {
                                for (Cookie cookie : cookies) {
                                    if (SecurityConstants.JWT_COOKIE_NAME.equals(cookie.getName())) token = cookie.getValue();
                                }
                            }
                        }
                        if (token == null) token = UriComponentsBuilder.fromUri(request.getURI()).build()
                                .getQueryParams().getFirst("token");
                        if (!isCurrent(token)) {
                            response.setStatusCode(HttpStatus.UNAUTHORIZED);
                            return false;
                        }
                        attributes.put("wsToken", token);
                        return true;
                    }
                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                            WebSocketHandler handler, Exception exception) { }
                }).withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) return message;
                String id = accessor.getSessionId();
                if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
                    tokens.remove(id);
                    return message;
                }
                String token = tokens.get(id);
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String header = accessor.getFirstNativeHeader("Authorization");
                    token = header != null && header.startsWith("Bearer ") ? header.substring(7)
                            : accessor.getSessionAttributes() == null ? null
                            : (String) accessor.getSessionAttributes().get("wsToken");
                }
                if (!isCurrent(token)) throw new org.springframework.security.access.AccessDeniedException("Session expired");
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    tokens.put(id, token);
                    String email = jwtUtil.extractEmail(token);
                    accessor.setUser(() -> email);
                }
                if (StompCommand.SEND.equals(accessor.getCommand()) ||
                        (StompCommand.SUBSCRIBE.equals(accessor.getCommand()) &&
                                !"/user/queue/notifications".equals(accessor.getDestination()))) {
                    throw new org.springframework.security.access.AccessDeniedException("Destination forbidden");
                }
                return message;
            }
        });
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                if (org.springframework.messaging.simp.SimpMessageType.MESSAGE.equals(accessor.getMessageType()) && !isCurrent(tokens.get(accessor.getSessionId()))) {
                    return null;
                }
                return message;
            }
        });
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }
            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                tokens.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        });
    }

    @Scheduled(fixedDelay = 30_000)
    public void closeRevokedSessions() {
        sessions.values().forEach(session -> {
            if (!isCurrent((String) session.getAttributes().get("wsToken"))) {
                try { session.close(CloseStatus.POLICY_VIOLATION); }
                catch (java.io.IOException ignored) { }
            }
        });
    }
}
