package com.nudge.controller;

import com.nudge.dto.EmailCreateRequest;
import com.nudge.dto.EmailDTO;
import com.nudge.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD for tracked emails. All endpoints require a valid JWT.
 */
@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private final EmailService emailService;

    public EmailController(EmailService emailService) {
        this.emailService = emailService;
    }

    /** GET /api/emails — List all tracked emails for the current user */
    @GetMapping
    public ResponseEntity<List<EmailDTO>> listEmails(@AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(emailService.getEmailsForUser(user.getUsername()));
    }

    /**
     * POST /api/emails — Register a new email for tracking.
     * Returns an EmailDTO containing the tracking pixel URL to embed in the email body.
     */
    @PostMapping
    public ResponseEntity<EmailDTO> createEmail(@AuthenticationPrincipal UserDetails user,
                                                @Valid @RequestBody EmailCreateRequest request) {
        EmailDTO created = emailService.createTrackedEmail(user.getUsername(), request);
        return ResponseEntity.ok(created);
    }

    /** GET /api/emails/{id} — Get details for a single tracked email */
    @GetMapping("/{id}")
    public ResponseEntity<EmailDTO> getEmail(@AuthenticationPrincipal UserDetails user,
                                             @PathVariable Long id) {
        return ResponseEntity.ok(emailService.getEmailById(id, user.getUsername()));
    }
}
