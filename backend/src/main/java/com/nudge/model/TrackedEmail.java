package com.nudge.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Represents an outgoing email that has been registered for tracking.
 * Each email gets a unique trackingId embedded in the tracking pixel URL.
 *
 * A7: Removed the bidirectional @OneToMany events collection — it was never
 * used (the service queries events directly via TrackingEventRepository) and
 * forced Hibernate to maintain an extra collection on every load.
 */
@Entity
@Table(
    name = "tracked_emails",
    indexes = {
        @Index(name = "idx_tracked_emails_user_id",     columnList = "user_id"),
        @Index(name = "idx_tracked_emails_tracking_id", columnList = "tracking_id"),
        @Index(name = "idx_tracked_emails_archived_at", columnList = "archived_at")
    }
)
@Data
@NoArgsConstructor
public class TrackedEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The user who sent this email */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String subject;

    /** Email body — stored (encrypted) so AI can read it for follow-up generation */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** Who this email was sent to */
    private String recipientEmail;

    /**
     * Unique UUID embedded into the tracking pixel URL.
     * e.g. GET /track/open/{trackingId}
     */
    @Column(nullable = false, unique = true)
    private String trackingId;

    // Q5: Hibernate sets this from the DB clock, not JVM time
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * F1: Soft-delete support.
     * null = active, non-null = archived at this timestamp.
     */
    private LocalDateTime archivedAt;

    /**
     * F4: When the user has scheduled a follow-up reminder.
     * null = no reminder scheduled.
     */
    private LocalDateTime scheduledFollowUpAt;
}
