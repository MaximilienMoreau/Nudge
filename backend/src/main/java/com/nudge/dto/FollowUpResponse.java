package com.nudge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FollowUpResponse {
    /** AI-generated follow-up email body */
    private String followUpText;
    /** Suggested subject line */
    private String suggestedSubject;
}
