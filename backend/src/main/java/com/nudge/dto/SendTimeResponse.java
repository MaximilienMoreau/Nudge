package com.nudge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for the AI send-time optimization endpoint.
 * Returned by POST /api/ai/send-time.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendTimeResponse {

    /** Human-readable recommendation, e.g. "Tuesday at 10:00 AM" */
    private String suggestion;

    /** Day of week with the most opens, e.g. "Tuesday" */
    private String bestDay;

    /** Hour with the most opens, e.g. "10:00 AM" */
    private String bestHour;

    /** e.g. "Based on 14 open events" */
    private String rationale;

    /** false when the user has no tracking history yet */
    private boolean hasData;
}
