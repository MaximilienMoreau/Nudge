package com.nudge.service;

import com.nudge.dto.NotificationDTO;
import com.nudge.model.EventType;
import com.nudge.model.FilterReason;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Processes incoming tracking events (pixel loads / link clicks).
 * Records them in the database and fires a real-time WebSocket notification
 * to the email's owner.
 *
 *  The entire recordOpen method is @Transactional so the event save and
 *      the score computation happen in a single DB transaction.
 *
 *  Hot-path optimisation — instead of loading ALL events to compute the
 *      score, we run two COUNT queries and one findFirst query:
 *        countByEmailAndTypeAndSuspectedBotFalse(OPEN)  → genuine openCount
 *        countByEmailAndType(CLICK)                     → clickCount
 *        findFirstByEmail…SuspectedBotFalse…Desc(OPEN)  → lastOpenAt
 *      This keeps response time O(1) regardless of how many events exist.
 *
 *  X-Forwarded-For is only trusted when the request originates from a
 *      configured trusted proxy range; otherwise the raw remote address is used.
 *
 * BD:  Events that do not prove a read (scanners, Apple MPP, rapid re-fetches, the
 *      sender's own browser) are persisted with a filterReason and excluded from
 *      score/notification.
 */
@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

    /** How long after registration the sender's own pixel loads are ignored. */
    static final Duration SENDER_WINDOW = Duration.ofMinutes(5);

    private final TrackedEmailRepository  emailRepo;
    private final TrackingEventRepository eventRepo;
    private final LeadScoringService      leadScoringService;
    private final NotificationService     notificationService;
    private final BotDetectionService     botDetectionService;
    private final Set<String>             trustedProxies;

    public TrackingService(TrackedEmailRepository emailRepo,
                           TrackingEventRepository eventRepo,
                           LeadScoringService leadScoringService,
                           NotificationService notificationService,
                           BotDetectionService botDetectionService,
                           @Qualifier("trustedProxySet") Set<String> trustedProxies) {
        this.emailRepo           = emailRepo;
        this.eventRepo           = eventRepo;
        this.leadScoringService  = leadScoringService;
        this.notificationService = notificationService;
        this.botDetectionService = botDetectionService;
        this.trustedProxies      = trustedProxies;
    }

    /**
     * Record an OPEN event triggered by a tracking pixel load.
     *
     * BD:  The event is classified before being saved. Filtered events are stored
     *      for audit but do not contribute to the lead score and do not trigger a
     *      WebSocket notification.
     *
     *  Score computed from 2 COUNT queries (genuine opens only) — O(1).
     *
     * @param trackingId UUID embedded in the pixel URL
     * @param request    HTTP request for IP/User-Agent extraction
     * @return true if the email was found and event recorded, false otherwise
     */
    @Transactional
    public boolean recordOpen(String trackingId, HttpServletRequest request) {
        return emailRepo.findByTrackingId(trackingId).map(email -> {
            String userAgent    = request.getHeader("User-Agent");
            String ip           = IpUtils.clientIp(request, trustedProxies);
            FilterReason reason = isSenderComposing(email, ip, userAgent)
                    ? FilterReason.SENDER
                    : botDetectionService.classifyOpen(userAgent, trackingId, email.getRecipientEmail());

            TrackingEvent event = new TrackingEvent();
            event.setEmail(email);
            event.setType(EventType.OPEN);
            event.setIpAddress(ip);
            event.setUserAgent(userAgent);
            event.filterAs(reason);
            eventRepo.save(event);

            if (reason != null) {
                log.info("Open not counted ({}) for trackingId={}", reason, trackingId);
                return true;
            }

            // COUNT genuine opens only — O(1) regardless of event history
            long openCount  = eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.OPEN);
            long clickCount = eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.CLICK);
            int  score      = leadScoringService.computeScore(openCount, clickCount, event.getTimestamp());

            log.info("Email opened: '{}' by {} (genuine opens: {}, score: {})",
                    email.getSubject(), email.getRecipientEmail(), openCount, score);

            fireNotification(email, EventType.OPEN, openCount, score);
            return true;
        }).orElse(false);
    }

    /**
     * Record a CLICK event triggered by a tracked link redirect.
     *
     * Known scanner User-Agents are persisted as suspected bots and excluded
     * from notifications and scores. Timing heuristics are reserved for opens.
     *
     * COUNT + single-row findFirst — avoids loading all events.
     *
     * @param trackingId UUID identifying the email
     * @param request    HTTP request for IP/User-Agent
     * @return non-null sentinel when the email was found, null otherwise
     */
    @Transactional
    public boolean recordClick(String trackingId, HttpServletRequest request) {
        return emailRepo.findByTrackingId(trackingId).map(email -> {
            TrackingEvent event = new TrackingEvent();
            event.setEmail(email);
            event.setType(EventType.CLICK);
            event.setIpAddress(IpUtils.clientIp(request, trustedProxies));
            event.setUserAgent(request.getHeader("User-Agent"));
            if (botDetectionService.matchesKnownBot(request.getHeader("User-Agent"))) {
                event.filterAs(FilterReason.SCANNER);
            }
            eventRepo.save(event);
            if (event.isSuspectedBot()) return true;

            // P4 + BD: genuine open count + last genuine open timestamp
            long openCount  = eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.OPEN);
            long clickCount = eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.CLICK);
            LocalDateTime lastOpen = eventRepo
                    .findFirstByEmailAndTypeAndSuspectedBotFalseOrderByTimestampDesc(email, EventType.OPEN)
                    .map(TrackingEvent::getTimestamp)
                    .orElse(null);
            int score = leadScoringService.computeScore(openCount, clickCount, lastOpen);

            log.info("Link clicked in email '{}' by {}", email.getSubject(), email.getRecipientEmail());

            fireNotification(email, EventType.CLICK, openCount, score);
            return true;
        }).orElse(false);
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
        Runnable deliver = () -> {
            try { notificationService.notifyUser(ownerEmail, notification); }
            catch (Exception error) { log.error("Notification delivery failed for email id={}", email.getId(), error); }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { deliver.run(); }
                });
        } else {
            deliver.run();
        }
    }

    /**
     * The sender's editor can load the pixel before the email is even sent. The
     * extension prevents it; this covers a pixel pasted by hand. Limited to a short
     * window because a colleague may share the sender's IP and browser version.
     */
    private static boolean isSenderComposing(TrackedEmail email, String ip, String userAgent) {
        return email.getCreatedAt() != null
                && email.getCreatedAt().isAfter(LocalDateTime.now().minus(SENDER_WINDOW))
                && ip != null && ip.equals(email.getSenderIp())
                && userAgent != null && userAgent.equals(email.getSenderUserAgent());
    }
}
