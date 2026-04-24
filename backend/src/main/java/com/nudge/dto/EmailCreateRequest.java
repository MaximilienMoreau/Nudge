package com.nudge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Request body when registering a new email for tracking. */
@Data
public class EmailCreateRequest {

    @NotBlank
    @Size(max = 500, message = "Subject must be under 500 characters")
    private String subject;

    /** Full email body — used later for AI follow-up generation (stored encrypted). */
    @Size(max = 100_000, message = "Content must be under 100 000 characters")
    private String content;

    /**
     * Single recipient (backward compatible with the Chrome extension).
     * Used when recipientEmails is not provided.
     */
    @Email
    private String recipientEmail;

    /**
     * F3: Multiple recipients — each gets its own unique trackingId.
     * When provided and non-empty, recipientEmail is ignored.
     */
    private List<@Email String> recipientEmails;
}
