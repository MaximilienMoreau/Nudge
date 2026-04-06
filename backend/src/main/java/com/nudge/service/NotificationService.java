package com.nudge.service;

import com.nudge.dto.NotificationDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Sends real-time WebSocket notifications to specific users.
 *
 * Destination: /user/{email}/queue/notifications
 * The frontend subscribes to: /user/queue/notifications
 * Spring's UserDestinationMessageHandler routes it to the right session.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Push a notification to the user who owns the email.
     *
     * @param userEmail    the email address used as the WS principal
     * @param notification the payload to send
     */
    public void notifyUser(String userEmail, NotificationDTO notification) {
        log.info("Pushing notification to user {}: email '{}' opened ({} times)",
                userEmail, notification.getSubject(), notification.getOpenCount());
        messagingTemplate.convertAndSendToUser(userEmail, "/queue/notifications", notification);
    }
}
