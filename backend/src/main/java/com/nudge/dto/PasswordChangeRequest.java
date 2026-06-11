package com.nudge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request body for the change-password endpoint. */
@Data
public class PasswordChangeRequest {

    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = 8, message = "New password must be at least 8 characters")
    @jakarta.validation.constraints.Pattern(
        regexp = "^(?=.*[A-Z])(?=.*[\\d\\W]).{8,}$",
        message = "New password must contain at least one uppercase letter and one number or special character"
    )
    private String newPassword;
}
