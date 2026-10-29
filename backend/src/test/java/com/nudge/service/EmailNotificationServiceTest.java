package com.nudge.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailNotificationServiceTest {
    @Test
    void smtpFailurePropagatesSoScheduledReminderCanBeRetried() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailNotificationService service = new EmailNotificationService(sender);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "fromAddress", "nudge@example.com");
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://nudge.example");
        doThrow(new IllegalStateException("SMTP unavailable")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> service.sendFollowUpReminder("owner@example.com", "Proposal", "client@example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("delivery failed");
    }
}
