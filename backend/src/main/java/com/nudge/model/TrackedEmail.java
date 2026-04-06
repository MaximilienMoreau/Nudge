package com.nudge.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an outgoing email that has been registered for tracking.
 * Each email gets a unique trackingId that is embedded in the tracking pixel URL.
 */
@Entity
@Table(name = "tracked_emails")
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

    /** Email body — stored so AI can read it for follow-up generation */
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

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** All open/click events for this email */
    @OneToMany(mappedBy = "email", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TrackingEvent> events = new ArrayList<>();
}
