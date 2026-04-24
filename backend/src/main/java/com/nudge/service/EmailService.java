package com.nudge.service;

import com.nudge.dto.EmailCreateRequest;
import com.nudge.dto.EmailDTO;
import com.nudge.model.EventType;
import com.nudge.model.TrackedEmail;
import com.nudge.model.TrackingEvent;
import com.nudge.model.User;
import com.nudge.repository.TrackedEmailRepository;
import com.nudge.repository.TrackingEventRepository;
import com.nudge.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core email management service.
 *
 * Q4: N+1 queries fixed — events for all emails are batch-fetched in a single
 *     query, then grouped into a Map<emailId, List<event>> before DTO assembly.
 *
 * S8: Email content is encrypted at rest via EncryptionService.
 *
 * F1: Soft-delete via archivedAt timestamp.
 *
 * F3: Multi-recipient support — createTrackedEmail accepts a list of recipient
 *     emails and returns one EmailDTO per recipient.
 *
 * A3: getEmailsForUser supports pagination.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final TrackedEmailRepository emailRepo;
    private final TrackingEventRepository eventRepo;
    private final UserRepository userRepo;
    private final LeadScoringService leadScoringService;
    private final EncryptionService encryptionService;

    @Value("${app.base.url}")
    private String baseUrl;

    public EmailService(TrackedEmailRepository emailRepo,
                        TrackingEventRepository eventRepo,
                        UserRepository userRepo,
                        LeadScoringService leadScoringService,
                        EncryptionService encryptionService) {
        this.emailRepo         = emailRepo;
        this.eventRepo         = eventRepo;
        this.userRepo          = userRepo;
        this.leadScoringService = leadScoringService;
        this.encryptionService  = encryptionService;
    }

    /**
     * Register one or more emails for tracking.
     *
     * F3: If request.recipientEmails has multiple entries, one TrackedEmail is created
     *     per recipient, each with its own unique trackingId.
     *
     * S8: Email content is encrypted before persisting.
     *
     * @return list of EmailDTOs (one per recipient)
     */
    @Transactional
    public List<EmailDTO> createTrackedEmail(String userEmail, EmailCreateRequest request) {
        User user = userRepo.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));

        // F3: Determine effective recipient list
        List<String> recipients = request.getRecipientEmails();
        if (recipients == null || recipients.isEmpty()) {
            recipients = List.of(request.getRecipientEmail());
        }

        String encryptedContent = encryptionService.encrypt(request.getContent()); // S8

        List<EmailDTO> results = new ArrayList<>();
        for (String recipient : recipients) {
            TrackedEmail email = new TrackedEmail();
            email.setUser(user);
            email.setSubject(request.getSubject());
            email.setContent(encryptedContent);    // S8: stored encrypted
            email.setRecipientEmail(recipient);
            email.setTrackingId(UUID.randomUUID().toString());
            emailRepo.save(email);

            log.info("Created tracked email '{}' for user {} → {}", email.getSubject(), userEmail, recipient);
            results.add(toDTO(email, List.of())); // New email has no events yet
        }
        return results;
    }

    /**
     * A3: Paginated list of active tracked emails for the authenticated user.
     */
    public Page<EmailDTO> getEmailsForUser(String userEmail, Pageable pageable) {
        User user = userRepo.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));

        Page<TrackedEmail> page = emailRepo.findByUserAndArchivedAtIsNullOrderByCreatedAtDesc(user, pageable);

        // Q4: Batch-fetch all events for the current page in one query
        List<TrackedEmail> emails = page.getContent();
        Map<Long, List<TrackingEvent>> eventsByEmail = batchFetchEvents(emails);

        return page.map(email -> toDTO(email, eventsByEmail.getOrDefault(email.getId(), List.of())));
    }

    /** Retrieve a single email DTO by ID (validates ownership). */
    public EmailDTO getEmailById(Long emailId, String userEmail) {
        TrackedEmail email = findAndVerify(emailId, userEmail);
        List<TrackingEvent> events = eventRepo.findByEmailOrderByTimestampDesc(email);
        return toDTO(email, events);
    }

    /**
     * F1: Soft-delete an email. Sets archivedAt to now; the row is kept in the DB.
     * Throws SecurityException if the email does not belong to the authenticated user.
     */
    @Transactional
    public void archiveEmail(Long emailId, String userEmail) {
        TrackedEmail email = findAndVerify(emailId, userEmail);
        email.setArchivedAt(LocalDateTime.now());
        emailRepo.save(email);
        log.info("Email {} archived by {}", emailId, userEmail);
    }

    /** Return all archived emails for the authenticated user. */
    public List<EmailDTO> getArchivedEmailsForUser(String userEmail) {
        User user = userRepo.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
        List<TrackedEmail> emails = emailRepo.findByUserAndArchivedAtIsNotNullOrderByArchivedAtDesc(user);
        Map<Long, List<TrackingEvent>> eventsByEmail = batchFetchEvents(emails);
        return emails.stream()
                .map(e -> toDTO(e, eventsByEmail.getOrDefault(e.getId(), List.of())))
                .collect(Collectors.toList());
    }

    /** Restore a soft-deleted email (clears archivedAt). */
    @Transactional
    public void restoreEmail(Long emailId, String userEmail) {
        TrackedEmail email = findAndVerify(emailId, userEmail);
        email.setArchivedAt(null);
        emailRepo.save(email);
        log.info("Email {} restored by {}", emailId, userEmail);
    }

    /** Permanently delete an email and all its tracking events. */
    @Transactional
    public void permanentlyDeleteEmail(Long emailId, String userEmail) {
        TrackedEmail email = findAndVerify(emailId, userEmail);
        emailRepo.delete(email);
        log.info("Email {} permanently deleted by {}", emailId, userEmail);
    }

    /**
     * F4: Schedule a follow-up reminder for an email.
     */
    @Transactional
    public void scheduleFollowUp(Long emailId, String userEmail, LocalDateTime scheduledAt) {
        TrackedEmail email = findAndVerify(emailId, userEmail);
        email.setScheduledFollowUpAt(scheduledAt);
        emailRepo.save(email);
        log.info("Follow-up scheduled for email {} at {}", emailId, scheduledAt);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private TrackedEmail findAndVerify(Long emailId, String userEmail) {
        TrackedEmail email = emailRepo.findById(emailId)
                .orElseThrow(() -> new IllegalArgumentException("Email not found: " + emailId));
        if (!email.getUser().getEmail().equals(userEmail)) {
            throw new SecurityException("Access denied");
        }
        return email;
    }

    /**
     * Q4: Batch-fetch events for a list of emails in one DB round-trip,
     * then group them by email ID.
     */
    private Map<Long, List<TrackingEvent>> batchFetchEvents(List<TrackedEmail> emails) {
        if (emails.isEmpty()) return Map.of();
        List<TrackingEvent> allEvents = eventRepo.findByEmailInOrderByTimestampDesc(emails);
        return allEvents.stream().collect(
                Collectors.groupingBy(e -> e.getEmail().getId())
        );
    }

    /** Build the full DTO from the email and its pre-fetched events. */
    private EmailDTO toDTO(TrackedEmail email, List<TrackingEvent> events) {
        List<TrackingEvent> opens = events.stream()
                .filter(e -> e.getType() == EventType.OPEN)
                .collect(Collectors.toList());
        List<TrackingEvent> clicks = events.stream()
                .filter(e -> e.getType() == EventType.CLICK)
                .collect(Collectors.toList());

        int openCount  = opens.size();
        int clickCount = clicks.size();
        LocalDateTime lastOpenedAt  = opens.isEmpty()  ? null : opens.get(0).getTimestamp();
        LocalDateTime lastClickedAt = clicks.isEmpty() ? null : clicks.get(0).getTimestamp();
        int score = leadScoringService.computeScore(events);

        EmailDTO dto = new EmailDTO();
        dto.setId(email.getId());
        dto.setSubject(email.getSubject());
        // S8: decrypt content before sending to frontend
        dto.setContent(encryptionService.decrypt(email.getContent()));
        dto.setRecipientEmail(email.getRecipientEmail());
        dto.setTrackingId(email.getTrackingId());
        dto.setCreatedAt(email.getCreatedAt());
        dto.setArchivedAt(email.getArchivedAt());
        dto.setOpenCount(openCount);
        dto.setLastOpenedAt(lastOpenedAt);
        dto.setClickCount(clickCount);
        dto.setLastClickedAt(lastClickedAt);
        dto.setLeadScore(score);
        dto.setStatus(resolveStatus(openCount));
        dto.setTrackingPixelUrl(buildPixelUrl(email.getTrackingId()));
        dto.setClickTrackingBaseUrl(buildClickBaseUrl(email.getTrackingId()));

        return dto;
    }

    private String resolveStatus(int openCount) {
        if (openCount == 0) return "Not Opened";
        if (openCount == 1) return "Opened";
        return "Opened Multiple Times";
    }

    private String buildPixelUrl(String trackingId) {
        return baseUrl + "/track/open/" + trackingId;
    }

    private String buildClickBaseUrl(String trackingId) {
        return baseUrl + "/track/click/" + trackingId + "?url=";
    }
}
