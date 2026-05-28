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

import com.nudge.util.IpUtils;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Processes incoming tracking events (pixel loads / link clicks).
 * Records them in the database and fires a real-time WebSocket notification
 * to the email's owner.
 *
 * Q6:  The entire recordOpen method is @Transactional so the event save and
 *      the score computation happen in a single DB transaction.
 *
 * P4:  Hot-path optimisation — instead of loading ALL events to compute the
 *      score, we run two COUNT queries and one findFirst query:
 *        countByEmailAndType(OPEN)   → openCount
 *        countByEmailAndType(CLICK)  → clickCount
 *        findFirstByEmail…Desc(OPEN) → lastOpenAt
 *      This keeps response time O(1) regardless of how many events exist.
 *
 * S4:  X-Forwarded-For is only trusted when the request originates from a
 *      configured trusted proxy range; otherwise the raw remote address is used.
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
     * P4: Score is computed from 2 COUNT queries instead of loading all events.
     *     lastOpenAt = now (we just recorded the open, no extra query needed).
     *
     * @param trackingId UUID embedded in the pixel URL
     * @param request    HTTP request for IP/User-Agent extraction
     * @return true if the email was found and event recorded, false otherwise
     */
    @Transactional          // Q6: single transaction for save + score recompute
    public boolean recordOpen(String trackingId, HttpServletRequest request) {
        return emailRepo.findByTrackingId(trackingId).map(email -> {
            LocalDateTime now = LocalDateTime.now();

            TrackingEvent event = new TrackingEvent();
            event.setEmail(email);
            event.setType(EventType.OPEN);
            event.setTimestamp(now);
            event.setIpAddress(extractIp(request));
            event.setUserAgent(request.getHeader("User-Agent"));
            eventRepo.save(event);

            // P4: two COUNT queries — O(1) regardless of event history
            long openCount  = eventRepo.countByEmailAndType(email, EventType.OPEN);
            long clickCount = eventRepo.countByEmailAndType(email, EventType.CLICK);
            // lastOpen is 'now' — we just persisted the open event above
            int score = leadScoringService.computeScore(openCount, clickCount, now);

            log.info("Email opened: '{}' by {} (total opens: {}, score: {})",
                    email.getSubject(), email.getRecipientEmail(), openCount, score);

            fireNotification(email, EventType.OPEN, openCount, score);
            return true;
        }).orElse(false);
    }

    /**
     * Record a CLICK event triggered by a tracked link redirect.
     *
     * P4: Score computed from COUNT + findFirst — avoids loading all events.
     *
     * @param trackingId UUID identifying the email
     * @param request    HTTP request for IP/User-Agent
     * @return non-null sentinel when the email was found, null otherwise
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

            // P4: COUNT + single-row findFirst — O(1)
            long openCount  = eventRepo.countByEmailAndType(email, EventType.OPEN);
            long clickCount = eventRepo.countByEmailAndType(email, EventType.CLICK);
            LocalDateTime lastOpen = eventRepo
                    .findFirstByEmailAndTypeOrderByTimestampDesc(email, EventType.OPEN)
                    .map(TrackingEvent::getTimestamp)
                    .orElse(null);
            int score = leadScoringService.computeScore(openCount, clickCount, lastOpen);

            log.info("Link clicked in email '{}' by {}", email.getSubject(), email.getRecipientEmail());

            fireNotification(email, EventType.CLICK, openCount, score);
            return "clicked"; // caller uses the ?url= param for the actual redirect
        }).orElse(null);
    }

    private void fireNotification(TrackedEmail email, EventType eventType, long openCount, int score) {
        String ownerEmail = email.getUser().getEmail();
        String notifType  = eventType == EventType.CLICK ? "EMAIL_CLICKED" : "EMAIL_OPENED";
        NotificationDTO notification = new NotificationDTO(
                notifType,
                email.getId(),
                email.getSubject(),
                email.getRecipientEmail(),
                (int) openCount,
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
        for (String trusted : trustedProxies) {
            if (IpUtils.matches(ip, trusted)) return true;
        }
        return false;
    }
}
