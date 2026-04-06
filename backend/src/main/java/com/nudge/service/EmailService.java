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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core email management service.
 * Handles registration of emails for tracking and assembling the EmailDTO.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final TrackedEmailRepository emailRepo;
    private final TrackingEventRepository eventRepo;
    private final UserRepository userRepo;
    private final LeadScoringService leadScoringService;

    @Value("${app.base.url}")
    private String baseUrl;

    public EmailService(TrackedEmailRepository emailRepo,
                        TrackingEventRepository eventRepo,
                        UserRepository userRepo,
                        LeadScoringService leadScoringService) {
        this.emailRepo = emailRepo;
        this.eventRepo = eventRepo;
        this.userRepo = userRepo;
        this.leadScoringService = leadScoringService;
    }

    /**
     * Register a new email for tracking.
     * Generates a unique trackingId (UUID) and returns the full DTO
     * which includes the tracking pixel URL to embed in the email body.
     */
    public EmailDTO createTrackedEmail(String userEmail, EmailCreateRequest request) {
        User user = userRepo.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));

        TrackedEmail email = new TrackedEmail();
        email.setUser(user);
        email.setSubject(request.getSubject());
        email.setContent(request.getContent());
        email.setRecipientEmail(request.getRecipientEmail());
        email.setTrackingId(UUID.randomUUID().toString());
        emailRepo.save(email);

        log.info("Created tracked email '{}' for user {}", email.getSubject(), userEmail);
        return toDTO(email);
    }

    /** Retrieve all tracked emails for the authenticated user. */
    public List<EmailDTO> getEmailsForUser(String userEmail) {
        User user = userRepo.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
        return emailRepo.findByUserOrderByCreatedAtDesc(user).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Retrieve a single email DTO by ID (validates ownership). */
    public EmailDTO getEmailById(Long emailId, String userEmail) {
        TrackedEmail email = emailRepo.findById(emailId)
                .orElseThrow(() -> new IllegalArgumentException("Email not found: " + emailId));
        if (!email.getUser().getEmail().equals(userEmail)) {
            throw new SecurityException("Access denied");
        }
        return toDTO(email);
    }

    // ── Private helpers ──────────────────────────────────────────

    /** Build the full DTO, computing stats and lead score from events. */
    private EmailDTO toDTO(TrackedEmail email) {
        List<TrackingEvent> events = eventRepo.findByEmailOrderByTimestampDesc(email);
        List<TrackingEvent> opens = events.stream()
                .filter(e -> e.getType() == EventType.OPEN)
                .collect(Collectors.toList());

        int openCount = opens.size();
        LocalDateTime lastOpenedAt = opens.isEmpty() ? null : opens.get(0).getTimestamp();
        int score = leadScoringService.computeScore(events);

        EmailDTO dto = new EmailDTO();
        dto.setId(email.getId());
        dto.setSubject(email.getSubject());
        dto.setContent(email.getContent());
        dto.setRecipientEmail(email.getRecipientEmail());
        dto.setTrackingId(email.getTrackingId());
        dto.setCreatedAt(email.getCreatedAt());
        dto.setOpenCount(openCount);
        dto.setLastOpenedAt(lastOpenedAt);
        dto.setLeadScore(score);
        dto.setStatus(resolveStatus(openCount));
        dto.setTrackingPixelUrl(buildPixelUrl(email.getTrackingId()));

        return dto;
    }

    private String resolveStatus(int openCount) {
        if (openCount == 0)  return "Not Opened";
        if (openCount == 1)  return "Opened";
        return "Opened Multiple Times";
    }

    private String buildPixelUrl(String trackingId) {
        return baseUrl + "/track/open/" + trackingId;
    }
}
