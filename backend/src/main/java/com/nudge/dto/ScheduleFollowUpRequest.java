package com.nudge.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Request body for POST /api/emails/{id}/schedule.
 *
 * @Future ensures validation is handled declaratively by the framework,
 * replacing the manual LocalDateTime.now() comparison in the controller.
 */
@Data
public class ScheduleFollowUpRequest {

    @NotNull(message = "scheduledAt is required")
    @Future(message = "scheduledAt must be in the future")
    private LocalDateTime scheduledAt;
}
