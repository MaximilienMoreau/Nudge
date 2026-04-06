package com.nudge.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Outbound representation of a tracked email sent to the frontend.
 * Includes computed fields like openCount and leadScore.
 */
@Data
public class EmailDTO {
    private Long id;
    private String subject;
    private String content;
    private String recipientEmail;
    private String trackingId;
    private LocalDateTime createdAt;

    /** How many times the tracking pixel has been loaded */
    private int openCount;

    /** Timestamp of the most recent open event */
    private LocalDateTime lastOpenedAt;

    /**
     * Computed Reply Probability Score (0–100).
     * Based on open count, recency, and frequency.
     */
    private int leadScore;

    /** Human-readable status badge */
    private String status; // "Not Opened", "Opened", "Opened Multiple Times"

    /** The full tracking pixel URL to embed in outgoing emails */
    private String trackingPixelUrl;
}
