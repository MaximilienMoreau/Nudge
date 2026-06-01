package com.nudge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String email;
    private Long userId;
    private LocalDateTime createdAt;
}
