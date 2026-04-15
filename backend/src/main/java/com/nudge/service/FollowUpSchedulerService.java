package com.nudge.service;

import com.nudge.model.TrackedEmail;
import com.nudge.repository.TrackedEmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F4: Scans for emails with a past scheduledFollowUpAt and notifies the owner.
 *
 * The check interval is configurable via nudge.followup.scheduler.interval-ms
 * (default: 1 hour in production, useful to lower in dev for testing).
 *
 * After notifying, the scheduledFollowUpAt is cleared so the reminder fires only once.
 */
@Service
@EnableScheduling
public class FollowUpSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpSchedulerService.class);

    private final TrackedEmailRepository emailRepo;
    private final NotificationService    notificationService;
    private final EmailNotificationService emailNotificationService;

    public FollowUpSchedulerService(TrackedEmailRepository emailRepo,
                                    NotificationService notificationService,
                                    EmailNotificationService emailNotificationService) {
        this.emailRepo               = emailRepo;
        this.notificationService     = notificationService;
        this.emailNotificationService = emailNotificationService;
    }

    /** Fixed-delay scan; interval configured in application.properties. */
    @Scheduled(fixedDelayString = "${nudge.followup.scheduler.interval-ms:3600000}")
    public void checkDueFollowUps() {
        List<TrackedEmail> due = emailRepo.findByScheduledFollowUpAtIsNotNullAndArchivedAtIsNull();
        LocalDateTime now = LocalDateTime.now();

        for (TrackedEmail email : due) {
            if (email.getScheduledFollowUpAt() == null || email.getScheduledFollowUpAt().isAfter(now)) {
                continue; // Not due yet
            }

            String ownerEmail = email.getUser().getEmail();
            log.info("Follow-up reminder due for email '{}' (owner: {})", email.getSubject(), ownerEmail);

            // Push real-time WebSocket notification
            com.nudge.dto.NotificationDTO notification = new com.nudge.dto.NotificationDTO(
                    "FOLLOW_UP_REMINDER",
                    email.getId(),
                    email.getSubject(),
                    email.getRecipientEmail(),
                    0, 0,
                    now
            );
            notificationService.notifyUser(ownerEmail, notification);

            // F5: Also send an email notification as fallback
            emailNotificationService.sendFollowUpReminder(ownerEmail, email.getSubject(), email.getRecipientEmail());

            // Clear the reminder so it fires only once
            email.setScheduledFollowUpAt(null);
            emailRepo.save(email);
        }
    }
}
