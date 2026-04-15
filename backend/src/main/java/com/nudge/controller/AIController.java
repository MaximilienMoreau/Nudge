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
 * AI-powered follow-up generation and send-time endpoints.
 *
 * S9: engagementScore and openCount are no longer supplied by the client.
 *     AIService fetches real values from the DB using emailId.
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
     * Requires JWT. The request body only needs emailId and daysSinceSent.
     * The server computes openCount and engagementScore from the database.
     */
    @PostMapping("/followup")
    public ResponseEntity<FollowUpResponse> generateFollowUp(
            @Valid @RequestBody FollowUpRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(aiService.generateFollowUp(request, principal.getUsername()));
    }

    /**
     * POST /api/ai/send-time
     *
     * Analyses historical open events and returns the best day × hour to send.
     * No request body required — user identity comes from the JWT.
     */
    @PostMapping("/send-time")
    public ResponseEntity<SendTimeResponse> suggestSendTime(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(aiService.suggestSendTime(principal.getUsername()));
    }
}
