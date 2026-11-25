package com.nudge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @Email @NotBlank
    private String email;
    @NotBlank
    private String password;
    /** Issues a long-lived bearer token and no cookie. Used by the browser extension. */
    private boolean rememberMe;
}
