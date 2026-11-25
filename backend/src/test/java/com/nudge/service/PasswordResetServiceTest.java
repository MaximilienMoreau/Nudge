package com.nudge.service;

import com.nudge.model.User;
import com.nudge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    final UserRepository users = mock(UserRepository.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final EmailNotificationService mail = mock(EmailNotificationService.class);
    final User user = new User();

    @BeforeEach
    void setUp() {
        user.setEmail("me@example.com");
        user.setPasswordHash("old");
        user.setTokenVersion(4);
        when(users.findByEmail("me@example.com")).thenReturn(Optional.of(user));
        when(users.findByResetTokenHash(any())).thenAnswer(call ->
                call.getArgument(0).equals(user.getResetTokenHash()) ? Optional.of(user) : Optional.empty());
        when(encoder.encode("NewPassword1!")).thenReturn("new");
        when(mail.isEnabled()).thenReturn(true);
    }

    PasswordResetService service(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new PasswordResetService(users, encoder, mail, environment, "https://nudge.example");
    }

    String requestToken(PasswordResetService service) {
        service.request("me@example.com");
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mail, timeout(2000)).sendPasswordReset(eq("me@example.com"), link.capture(), anyLong());
        assertThat(link.getValue()).startsWith("https://nudge.example/reset.html#token=");
        return link.getValue().substring(link.getValue().indexOf("token=") + 6);
    }

    @Test
    void linkChangesThePasswordOnceAndRevokesSessions() {
        PasswordResetService service = service("prod");
        String token = requestToken(service);
        assertThat(user.getResetTokenHash()).isNotEqualTo(token);

        service.reset(token, "NewPassword1!");

        assertThat(user.getPasswordHash()).isEqualTo("new");
        assertThat(user.getTokenVersion()).isEqualTo(5);
        assertThatThrownBy(() -> service.reset(token, "NewPassword1!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredOrWrongTokenIsRefused() {
        PasswordResetService service = service("prod");
        String token = requestToken(service);
        assertThatThrownBy(() -> service.reset(token + "x", "NewPassword1!")).isInstanceOf(IllegalArgumentException.class);
        user.setResetTokenExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> service.reset(token, "NewPassword1!")).isInstanceOf(IllegalArgumentException.class);
        assertThat(user.getPasswordHash()).isEqualTo("old");
    }

    @Test
    void repeatedRequestsDoNotFloodTheMailbox() {
        PasswordResetService service = service("prod");
        requestToken(service);
        service.request("me@example.com");
        verify(mail, after(200).times(1)).sendPasswordReset(any(), any(), anyLong());
    }

    @Test
    void unknownAddressIsSilentlyIgnored() {
        assertThatCode(() -> service("prod").request("nobody@example.com")).doesNotThrowAnyException();
        verify(users, never()).save(any());
    }

    @Test
    void unavailableWithoutEmailExceptInDevelopment() {
        when(mail.isEnabled()).thenReturn(false);
        assertThat(service("prod").isAvailable()).isFalse();
        assertThat(service("dev").isAvailable()).isTrue();
    }
}
