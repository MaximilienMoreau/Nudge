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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Processes incoming tracking events (pixel loads / link clicks).
 * Records them in the database and fires a real-time WebSocket notification
 * to the email's owner.
 *
 * Q6: The entire recordOpen method is @Transactional so the event save and
 *     the score computation happen in a single DB transaction.
 *
 * S4: X-Forwarded-For is only trusted when the request originates from a
 *     configured trusted proxy range; otherwise the raw remote address is used.
 */
@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

    private final TrackedEmailRepository emailRepo;
    private final TrackingEventRepository eventRepo;
    private final LeadScoringService leadScoringService;
    private final NotificationService notificationService;
    private final Set<String> trustedProxies;

    public TrackingService(TrackedEmailRepository emailRepo,
                           TrackingEventRepository eventRepo,
                           LeadScoringService leadScoringService,
                           NotificationService notificationService,
                           @Qualifier("trustedProxySet") Set<String> trustedProxies) {
        this.emailRepo          = emailRepo;
        this.eventRepo          = eventRepo;
        this.leadScoringService = leadScoringService;
        this.notificationService = notificationService;
        this.trustedProxies     = trustedProxies;
    }

    /**
     * Record an OPEN event triggered by a tracking pixel load.
     *
     * @param trackingId UUID embedded in the pixel URL
     * @param request    HTTP request for IP/User-Agent extraction
     * @return true if the email was found and event recorded, false otherwise
     */
    @Transactional          // Q6: single transaction for save + score recompute
    public boolean recordOpen(String trackingId, HttpServletRequest request) {
        return emailRepo.findByTrackingId(trackingId).map(email -> {
            TrackingEvent event = new TrackingEvent();
            event.setEmail(email);
            event.setType(EventType.OPEN);
            event.setTimestamp(LocalDateTime.now());
            event.setIpAddress(extractIp(request));
            event.setUserAgent(request.getHeader("User-Agent"));
            eventRepo.save(event);

            List<TrackingEvent> allEvents = eventRepo.findByEmailOrderByTimestampDesc(email);
            int score = leadScoringService.computeScore(allEvents);
            int openCount = (int) allEvents.stream()
                    .filter(e -> e.getType() == EventType.OPEN)
                    .count();

            log.info("Email opened: '{}' by {} (total opens: {}, score: {})",
                    email.getSubject(), email.getRecipientEmail(), openCount, score);

            fireNotification(email, openCount, score);
            return true;
        }).orElse(false);
    }

    /**
     * Record a CLICK event triggered by a tracked link redirect.
     *
     * @param trackingId UUID identifying the email
     * @param request    HTTP request for IP/User-Agent
     * @return the original redirect URL, or null if email not found
     */
    @Transactional
    public String recordClick(String trackingId, HttpServletRequest request) {
        return emailRepo.findByTrackingId(trackingId).map(email -> {
            TrackingEvent event = new TrackingEvent();
            event.setEmail(email);
            event.setType(EventType.CLICK);
            event.setTimestamp(LocalDateTime.now());
            event.setIpAddress(extractIp(request));
            event.setUserAgent(request.getHeader("User-Agent"));
            eventRepo.save(event);

            List<TrackingEvent> allEvents = eventRepo.findByEmailOrderByTimestampDesc(email);
            int score = leadScoringService.computeScore(allEvents);

            log.info("Link clicked in email '{}' by {}", email.getSubject(), email.getRecipientEmail());

            fireNotification(email,
                    (int) allEvents.stream().filter(e -> e.getType() == EventType.OPEN).count(),
                    score);
            return "clicked"; // caller uses the ?url= param for the actual redirect
        }).orElse(null);
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
     * S4: Extract real IP, only trusting X-Forwarded-For when the direct
     * connection comes from a configured trusted proxy range.
     */
    private String extractIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String ip) {
        if (ip == null) return false;
        // Exact match or CIDR prefix match (simple implementation)
        for (String trusted : trustedProxies) {
            if (ip.equals(trusted) || ip.startsWith(trusted.replaceAll("/.*", ""))) {
                return true;
            }
        }
        return false;
    }
}
