package com.nudge.service;

import com.nudge.dto.NotificationDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Sends real-time WebSocket notifications to specific users.
 *
 * F5: Also triggers an email fallback notification via EmailNotificationService
 *     for open events. The email service gates itself on the
 *     nudge.notifications.email.enabled flag, so no SMTP config is needed in dev.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final EmailNotificationService emailNotificationService;

    public NotificationService(SimpMessagingTemplate messagingTemplate,
                                EmailNotificationService emailNotificationService) {
        this.messagingTemplate        = messagingTemplate;
        this.emailNotificationService = emailNotificationService;
    }

    /**
     * Push a notification to the user who owns the email.
     * Also sends an email notification as fallback (F5).
     */
    public void notifyUser(String userEmail, NotificationDTO notification) {
        log.info("Pushing notification to user {}: '{}' (type: {})",
                userEmail, notification.getSubject(), notification.getType());
        messagingTemplate.convertAndSendToUser(userEmail, "/queue/notifications", notification);

        // F5: Email fallback for open events (gated by config flag)
        if ("EMAIL_OPENED".equals(notification.getType())) {
            emailNotificationService.sendOpenNotification(
                    userEmail, notification.getSubject(), notification.getRecipientEmail());
        }
    }
}
