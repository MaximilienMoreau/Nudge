package com.nudge.controller;

import com.nudge.dto.FollowUpRequest;
import com.nudge.dto.FollowUpResponse;
import com.nudge.dto.SendTimeResponse;
import com.nudge.service.AIService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * AI-powered follow-up generation endpoint.
 */
@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final AIService aiService;

    public AIController(AIService aiService) {
        this.aiService = aiService;
    }

    /**
     * POST /api/ai/followup
     *
     * Given context about the original email and engagement data,
     * returns an AI-generated follow-up message and suggested subject line.
     *
     * Requires JWT authentication.
     */
    @PostMapping("/followup")
    public ResponseEntity<FollowUpResponse> generateFollowUp(@Valid @RequestBody FollowUpRequest request) {
        return ResponseEntity.ok(aiService.generateFollowUp(request));
    }

    /**
     * POST /api/ai/send-time
     *
     * Analyses the authenticated user's historical open events and returns the
     * day × hour combination with the highest recipient engagement as the
     * recommended time to send or follow up.
     *
     * No request body required — user identity comes from the JWT.
     */
    @PostMapping("/send-time")
    public ResponseEntity<SendTimeResponse> suggestSendTime(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(aiService.suggestSendTime(principal.getUsername()));
    }
}
