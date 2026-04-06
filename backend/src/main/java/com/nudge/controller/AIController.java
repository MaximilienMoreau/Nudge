package com.nudge.controller;

import com.nudge.dto.FollowUpRequest;
import com.nudge.dto.FollowUpResponse;
import com.nudge.service.AIService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
}
