package com.nudge.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A single tracking event (e.g. email open) for a TrackedEmail.
 * Each row represents one pixel load / click.
 */
@Entity
@Table(name = "tracking_events")
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

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    /** Recipient's IP address (for geo insights, optional) */
    private String ipAddress;

    /** Recipient's email client User-Agent string */
    private String userAgent;
}
