package com.nudge.service;

import com.nudge.dto.NotificationDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;

/**
 * Sends real-time WebSocket notifications to specific users.
 *
 * Also triggers an email fallback notification via EmailNotificationService
 *     for open events, but only when the user has no active WebSocket
 *     session (SimpUserRegistry) — otherwise they'd get the live push AND
 *     a duplicate email for the same event. The email service additionally
 *     gates itself on the nudge.notifications.email.enabled flag, so no
 *     SMTP config is needed in dev.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final EmailNotificationService emailNotificationService;
    private final SimpUserRegistry simpUserRegistry;

    public NotificationService(SimpMessagingTemplate messagingTemplate,
                                EmailNotificationService emailNotificationService,
                                SimpUserRegistry simpUserRegistry) {
        this.messagingTemplate        = messagingTemplate;
        this.emailNotificationService = emailNotificationService;
        this.simpUserRegistry         = simpUserRegistry;
    }

    /**
     * Push a notification to the user who owns the email.
     * Also sends an email notification as fallback (F5) when the user
     * has no active WebSocket session to receive the live push.
     */
    public void notifyUser(String userEmail, NotificationDTO notification) {
        log.info("Pushing notification to user {}: '{}' (type: {})",
                userEmail, notification.getSubject(), notification.getType());
        messagingTemplate.convertAndSendToUser(userEmail, "/queue/notifications", notification);

        // Email fallback for open events, only if no active WS session (gated by config flag)
        boolean hasActiveSession = simpUserRegistry.getUser(userEmail) != null;
        if ("EMAIL_OPENED".equals(notification.getType()) && !hasActiveSession) {
            emailNotificationService.sendOpenNotification(
                    userEmail, notification.getSubject(), notification.getRecipientEmail());
        }
    }
}
