package com.nudge.controller;

import com.nudge.dto.EmailCreateRequest;
import com.nudge.dto.EmailDTO;
import com.nudge.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * CRUD for tracked emails. All endpoints require a valid JWT.
 *
 * A3: GET /api/emails supports ?page= and ?size= pagination.
 * F1: DELETE /api/emails/{id} — soft-delete (sets archivedAt).
 * F3: POST /api/emails returns List<EmailDTO> (one per recipient).
 * F4: POST /api/emails/{id}/schedule — schedule a follow-up reminder.
 * F8: GET /api/emails/{id} is now used for the detail/drilldown view.
 */
@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private final EmailService emailService;

    public EmailController(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * GET /api/emails
     * A3: paginated; defaults to page 0, up to 50 emails per page.
     */
    @GetMapping
    public ResponseEntity<Page<EmailDTO>> listEmails(
            @AuthenticationPrincipal UserDetails user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        return ResponseEntity.ok(emailService.getEmailsForUser(user.getUsername(), pageable));
    }

    /**
     * POST /api/emails
     * F3: Returns a list — one EmailDTO per recipient.
     */
    @PostMapping
    public ResponseEntity<List<EmailDTO>> createEmail(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody EmailCreateRequest request) {
        List<EmailDTO> created = emailService.createTrackedEmail(user.getUsername(), request);
        return ResponseEntity.ok(created);
    }

    /**
     * GET /api/emails/{id}
     * F8: Detail view for a single tracked email (validates ownership).
     */
    @GetMapping("/{id}")
    public ResponseEntity<EmailDTO> getEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id) {
        return ResponseEntity.ok(emailService.getEmailById(id, user.getUsername()));
    }

    /**
     * DELETE /api/emails/{id}
     * F1: Soft-delete — sets archivedAt; the email is hidden from the list.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> archiveEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id) {
        emailService.archiveEmail(id, user.getUsername());
        return ResponseEntity.ok(Map.of("message", "Email archived"));
    }

    /**
     * GET /api/emails/archived
     * Returns all soft-deleted emails for the authenticated user.
     */
    @GetMapping("/archived")
    public ResponseEntity<List<EmailDTO>> listArchivedEmails(
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(emailService.getArchivedEmailsForUser(user.getUsername()));
    }

    /**
     * POST /api/emails/{id}/restore
     * Restores a previously archived email (clears archivedAt).
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<Map<String, String>> restoreEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id) {
        emailService.restoreEmail(id, user.getUsername());
        return ResponseEntity.ok(Map.of("message", "Email restored"));
    }

    /**
     * DELETE /api/emails/{id}/permanent
     * Permanently deletes an email and all its tracking events.
     */
    @DeleteMapping("/{id}/permanent")
    public ResponseEntity<Map<String, String>> permanentlyDeleteEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id) {
        emailService.permanentlyDeleteEmail(id, user.getUsername());
        return ResponseEntity.ok(Map.of("message", "Email permanently deleted"));
    }

    /**
     * POST /api/emails/{id}/schedule
     * F4: Schedule a follow-up reminder at the given ISO-8601 date-time.
     * Body: { "scheduledAt": "2026-04-20T09:00:00" }
     */
    @PostMapping("/{id}/schedule")
    public ResponseEntity<Map<String, String>> scheduleFollowUp(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String scheduledAt = body.get("scheduledAt");
        if (scheduledAt == null || scheduledAt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "scheduledAt is required"));
        }
        LocalDateTime parsedAt = LocalDateTime.parse(scheduledAt);
        if (!parsedAt.isAfter(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "scheduledAt must be in the future"));
        }
        emailService.scheduleFollowUp(id, user.getUsername(), parsedAt);
        return ResponseEntity.ok(Map.of("message", "Follow-up scheduled for " + scheduledAt));
    }
}
