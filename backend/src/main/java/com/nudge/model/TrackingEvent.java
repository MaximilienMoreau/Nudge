package com.nudge.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A single tracking event (e.g. email open or link click) for a TrackedEmail.
 * Each row represents one pixel load or link click.
 */
@Entity
@Table(
    name = "tracking_events",
    indexes = {
        @Index(name = "idx_tracking_events_email_id",   columnList = "email_id"),
        @Index(name = "idx_tracking_events_email_type", columnList = "email_id, type"),
        @Index(name = "idx_tracking_events_timestamp",  columnList = "timestamp")
    }
)
@Data
@NoArgsConstructor
public class TrackingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_id", nullable = false)
    private TrackedEmail email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    // Consistent with TrackedEmail.createdAt: set by Hibernate at INSERT, not at object construction
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;

    /** Recipient's IP address (for geo insights, optional) */
    private String ipAddress;

    /** Recipient's email client User-Agent string */
    private String userAgent;
}
