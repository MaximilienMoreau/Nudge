package com.nudge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Input for AI follow-up generation. */
@Data
public class FollowUpRequest {

    @NotNull
    private Long emailId; // Used to look up context if needed

    @NotBlank
    private String subject;

    @NotBlank
    private String originalContent;

    private String recipientEmail;

    /** Computed engagement/lead score (0–100) */
    private int engagementScore;

    /** Number of opens recorded */
    private int openCount;

    /** Days since the email was first sent */
    private int daysSinceSent;
}
