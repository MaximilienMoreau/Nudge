package com.nudge.service;

import com.nudge.dto.NotificationDTO;
import com.nudge.model.TrackedEmail;
import com.nudge.repository.TrackedEmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Scans for emails with a past scheduledFollowUpAt and notifies the owner.
 *
 * The check interval is configurable via nudge.followup.scheduler.interval-ms
 * (default: 1 hour in production, useful to lower in dev for testing).
 *
 * After notifying, the scheduledFollowUpAt is cleared so the reminder fires only once.
 *
 * Each email is read and saved in its own short transaction rather than one
 *     transaction spanning the whole batch — the WebSocket push and SMTP send
 *     in between are blocking I/O and must not hold a pooled DB connection open.
 *     If sending fails, the reminder is left uncleared so the next run retries it.
 */
@Service
public class FollowUpSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpSchedulerService.class);

    private final TrackedEmailRepository emailRepo;
    private final NotificationService    notificationService;
    private final EmailNotificationService emailNotificationService;
    private final TransactionTemplate    transactionTemplate;

    public FollowUpSchedulerService(TrackedEmailRepository emailRepo,
                                    NotificationService notificationService,
                                    EmailNotificationService emailNotificationService,
                                    PlatformTransactionManager transactionManager) {
        this.emailRepo               = emailRepo;
        this.notificationService     = notificationService;
        this.emailNotificationService = emailNotificationService;
        this.transactionTemplate     = new TransactionTemplate(transactionManager);
    }

    private static final int BATCH_SIZE = 100;

    /** Fixed-delay scan; interval configured in application.properties. */
    @Scheduled(fixedDelayString = "${nudge.followup.scheduler.interval-ms:3600000}")
    public void checkDueFollowUps() {
        LocalDateTime now = LocalDateTime.now();
        int page = 0;
        Slice<TrackedEmail> slice;

        do {
            int currentPage = page++;
            slice = transactionTemplate.execute(status -> emailRepo
                    .findByScheduledFollowUpAtIsNotNullAndArchivedAtIsNull(PageRequest.of(currentPage, BATCH_SIZE)));

            for (TrackedEmail email : slice.getContent()) {
                if (email.getScheduledFollowUpAt() == null || email.getScheduledFollowUpAt().isAfter(now)) {
                    continue; // Not due yet
                }
                processDueFollowUp(email, now);
            }
        } while (slice.hasNext());
    }

    private void processDueFollowUp(TrackedEmail email, LocalDateTime now) {
        try {
            // Resolve the lazy owner association inside a short transaction
            String ownerEmail = transactionTemplate.execute(status -> email.getUser().getEmail());
            log.info("Follow-up reminder due for email '{}' (owner: {})", email.getSubject(), ownerEmail);

            // Push real-time WebSocket notification
            NotificationDTO notification = new NotificationDTO(
                    "FOLLOW_UP_REMINDER",
                    email.getId(),
                    email.getSubject(),
                    email.getRecipientEmail(),
                    0, 0,
                    now
            );
            notificationService.notifyUser(ownerEmail, notification);

            // Also send an email notification as fallback
            emailNotificationService.sendFollowUpReminder(ownerEmail, email.getSubject(), email.getRecipientEmail());

            // Clear the reminder so it fires only once — only after sending succeeded
            transactionTemplate.executeWithoutResult(status -> {
                email.setScheduledFollowUpAt(null);
                emailRepo.save(email);
            });
        } catch (Exception e) {
            log.error("Failed to process follow-up reminder for email id={}: {}", email.getId(), e.getMessage());
            // Do not clear scheduledFollowUpAt — the next run retries
        }
    }
}
