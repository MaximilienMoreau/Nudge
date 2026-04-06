package com.nudge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Request body when registering a new email for tracking. */
@Data
public class EmailCreateRequest {

    @NotBlank
    private String subject;

    /** Full email body — used later for AI follow-up generation */
    private String content;

    @Email @NotBlank
    private String recipientEmail;
}
