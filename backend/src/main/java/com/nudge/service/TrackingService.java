package com.nudge.service;

import com.nudge.dto.NotificationDTO;
import com.nudge.model.EventType;
import com.nudge.model.TrackedEmail;
import com.nudge.model.TrackingEvent;
import com.nudge.repository.TrackedEmailRepository;
import com.nudge.repository.TrackingEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Processes incoming tracking events (pixel loads / link clicks).
 * Records them in the database and fires a real-time WebSocket notification
 * to the email's owner.
 */
@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

    private final TrackedEmailRepository emailRepo;
    private final TrackingEventRepository eventRepo;
    private final LeadScoringService leadScoringService;
    private final NotificationService notificationService;

    public TrackingService(TrackedEmailRepository emailRepo,
                           TrackingEventRepository eventRepo,
                           LeadScoringService leadScoringService,
                           NotificationService notificationService) {
        this.emailRepo = emailRepo;
        this.eventRepo = eventRepo;
        this.leadScoringService = leadScoringService;
        this.notificationService = notificationService;
    }

    /**
     * Record an OPEN event triggered by a tracking pixel load.
     *
     * @param trackingId UUID embedded in the pixel URL
     * @param request    HTTP request for IP/User-Agent extraction
     * @return true if the email was found and event recorded, false otherwise
     */
    public boolean recordOpen(String trackingId, HttpServletRequest request) {
        return emailRepo.findByTrackingId(trackingId).map(email -> {
            TrackingEvent event = new TrackingEvent();
            event.setEmail(email);
            event.setType(EventType.OPEN);
            event.setTimestamp(LocalDateTime.now());
            event.setIpAddress(extractIp(request));
            event.setUserAgent(request.getHeader("User-Agent"));
            eventRepo.save(event);

            // Recompute score after this new open
            List<TrackingEvent> allEvents = eventRepo.findByEmailOrderByTimestampDesc(email);
            int score = leadScoringService.computeScore(allEvents);
            int openCount = (int) allEvents.stream()
                    .filter(e -> e.getType() == EventType.OPEN)
                    .count();

            log.info("Email opened: '{}' by {} (total opens: {}, score: {})",
                    email.getSubject(), email.getRecipientEmail(), openCount, score);

            // Push real-time notification to the sender
            fireNotification(email, openCount, score);
            return true;
        }).orElse(false);
    }

    private void fireNotification(TrackedEmail email, int openCount, int score) {
        String ownerEmail = email.getUser().getEmail();
        NotificationDTO notification = new NotificationDTO(
                "EMAIL_OPENED",
                email.getId(),
                email.getSubject(),
                email.getRecipientEmail(),
                openCount,
                score,
                LocalDateTime.now()
        );
        notificationService.notifyUser(ownerEmail, notification);
    }

    /**
     * Extract real IP, respecting X-Forwarded-For when behind a proxy.
     */
    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take the first address in the chain (client IP)
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
