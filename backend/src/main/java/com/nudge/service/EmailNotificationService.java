package com.nudge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * F5: Email notification fallback.
 *
 * Sends plain-text email notifications when:
 *   - A tracked email is opened (and the recipient has no active WebSocket session)
 *   - A follow-up reminder becomes due (F4)
 *
 * Controlled by nudge.notifications.email.enabled=true|false.
 * In the dev profile this is false so no SMTP is required locally.
 */
@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailSender;

    @Value("${nudge.notifications.email.enabled:false}")
    private boolean enabled;

    @Value("${nudge.notifications.email.from:noreply@nudge.app}")
    private String fromAddress;

    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Notify the sender that their tracked email was opened. */
    public void sendOpenNotification(String toEmail, String emailSubject, String recipientEmail) {
        if (!enabled) return;
        send(
            toEmail,
            "📬 Your email was opened — Nudge",
            String.format(
                "Good news!\n\nYour email \"%s\" was just opened by %s.\n\n" +
                "Log in to Nudge to view the full engagement details and generate a follow-up.\n\nhttps://nudge.app/dashboard",
                emailSubject, recipientEmail
            )
        );
    }

    /** Notify the sender that a scheduled follow-up reminder is due. */
    public void sendFollowUpReminder(String toEmail, String emailSubject, String recipientEmail) {
        if (!enabled) return;
        send(
            toEmail,
            "⏰ Follow-up reminder — Nudge",
            String.format(
                "This is your follow-up reminder for the email \"%s\" sent to %s.\n\n" +
                "Log in to Nudge to generate an AI-powered follow-up message:\nhttps://nudge.app/dashboard",
                emailSubject, recipientEmail
            )
        );
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email notification sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send email notification to {}: {}", to, e.getMessage());
        }
    }
}
