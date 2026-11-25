package com.nudge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank
        @Pattern(
            regexp = "^(?=.*[A-Z])(?=.*[\\d\\W]).{8,}$",
            message = "Password must have at least 8 characters, one uppercase letter and one number or special character"
        )
        String newPassword) {}
