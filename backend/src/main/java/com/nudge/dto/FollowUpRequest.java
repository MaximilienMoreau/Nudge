package com.nudge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Input for AI follow-up generation.
 *
 * S9: engagementScore and openCount are no longer accepted from the client.
 *     The server recalculates them from the DB using emailId to prevent tampering.
 */
@Data
public class FollowUpRequest {

    @NotNull
    private Long emailId;

    /** Days since the email was first sent (computed client-side from createdAt). */
    private int daysSinceSent;
}
