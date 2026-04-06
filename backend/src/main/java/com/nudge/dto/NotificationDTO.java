package com.nudge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** WebSocket push message sent to the dashboard on email open. */
@Data
@AllArgsConstructor
public class NotificationDTO {
    private String type;           // "EMAIL_OPENED"
    private Long emailId;
    private String subject;
    private String recipientEmail;
    private int openCount;
    private int leadScore;
    private LocalDateTime timestamp;
}
