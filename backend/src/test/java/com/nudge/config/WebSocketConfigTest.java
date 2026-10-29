package com.nudge.config;

import com.nudge.model.User;
import com.nudge.repository.UserRepository;
import com.nudge.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketConfigTest {
    static class Registration extends ChannelRegistration {
        public java.util.List<org.springframework.messaging.support.ChannelInterceptor> guards() { return getInterceptors(); }
    }
    final JwtUtil jwt = mock(JwtUtil.class);
    final UserRepository users = mock(UserRepository.class);
    final WebSocketConfig config = new WebSocketConfig(jwt, users);

    void currentToken() {
        User user = new User(); user.setId(1L); user.setEmail("owner@example.com"); user.setTokenVersion(2);
        when(jwt.isValid("token")).thenReturn(true);
        when(jwt.extractEmail("token")).thenReturn(user.getEmail());
        when(jwt.extractUserId("token")).thenReturn(1L);
        when(jwt.extractTokenVersion("token")).thenReturn(2);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test
    void revokedAndDeletedAccountsCannotAuthenticate() {
        currentToken();
        assertThat(config.isCurrent("token")).isTrue();
        when(jwt.extractTokenVersion("token")).thenReturn(1);
        assertThat(config.isCurrent("token")).isFalse();
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.empty());
        assertThat(config.isCurrent("token")).isFalse();
    }

    @Test
    void rejectsBrokerInjectionAndSuppressesDeliveryAfterRevocation() {
        currentToken();
        Registration inbound = new Registration();
        config.configureClientInboundChannel(inbound);
        var guard = inbound.guards().get(0);
        var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("session"); connect.setSessionAttributes(Map.of("wsToken", "token")); connect.setLeaveMutable(true);
        guard.preSend(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()), mock(MessageChannel.class));
        var subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId("session"); subscribe.setDestination("/queue/notifications-owner@example.com");
        assertThatThrownBy(() -> guard.preSend(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()), mock(MessageChannel.class)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        Registration outbound = new Registration(); config.configureClientOutboundChannel(outbound);
        var message = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(org.springframework.messaging.simp.SimpMessageType.MESSAGE); message.setSessionId("session");
        var payload = MessageBuilder.createMessage(new byte[0], message.getMessageHeaders());
        assertThat(outbound.guards().get(0).preSend(payload, mock(MessageChannel.class))).isNotNull();
        when(jwt.extractTokenVersion("token")).thenReturn(1);
        assertThat(outbound.guards().get(0).preSend(payload, mock(MessageChannel.class))).isNull();
    }
}
