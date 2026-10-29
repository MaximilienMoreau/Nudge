package com.nudge.controller;

import com.nudge.dto.EmailCreateRequest;
import com.nudge.dto.EmailDTO;
import com.nudge.dto.ScheduleFollowUpRequest;
import com.nudge.service.EmailService;
import com.nudge.repository.DashboardRepository;
import jakarta.validation.Valid;
import com.nudge.dto.EmailPageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Note: GET /api/emails/archived is declared before /{id} so Spring MVC
// resolves the literal path before the template — no ambiguity.

/**
 * CRUD for tracked emails. All endpoints require a valid JWT.
 *
 * GET /api/emails supports ?page= and ?size= pagination.
 * DELETE /api/emails/{id} — soft-delete (sets archivedAt).
 * POST /api/emails returns List<EmailDTO> (one per recipient).
 * POST /api/emails/{id}/schedule — schedule a follow-up reminder.
 * GET /api/emails/{id} is now used for the detail/drilldown view.
 */
@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private final EmailService emailService;

    private final DashboardRepository dashboard;

    public EmailController(EmailService emailService, DashboardRepository dashboard) {
        this.emailService = emailService;
        this.dashboard = dashboard;
    }

    /**
     * GET /api/emails
     * paginated; defaults to page 0, up to 50 emails per page.
     */
    @GetMapping
    public ResponseEntity<EmailPageResponse> listEmails(
            @AuthenticationPrincipal UserDetails user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "false") boolean ascending) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        return ResponseEntity.ok(EmailPageResponse.of(dashboard.list(user.getUsername(), false, search, sort, ascending, pageable)));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(@AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(dashboard.stats(user.getUsername()));
    }

    /**
     * POST /api/emails
     * Returns a list — one EmailDTO per recipient.
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
     * Detail view for a single tracked email (validates ownership).
     */
    @GetMapping("/{id}")
    public ResponseEntity<EmailDTO> getEmail(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id) {
        return ResponseEntity.ok(emailService.getEmailById(id, user.getUsername()));
    }

    /**
     * DELETE /api/emails/{id}
     * Soft-delete — sets archivedAt; the email is hidden from the list.
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
     * Returns soft-deleted emails for the authenticated user; paginated to prevent OOM.
     */
    @GetMapping("/archived")
    public ResponseEntity<EmailPageResponse> listArchivedEmails(
            @AuthenticationPrincipal UserDetails user,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "100") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        return ResponseEntity.ok(EmailPageResponse.of(dashboard.list(user.getUsername(), true, "", "archivedAt", false, pageable)));
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
     * Schedule a follow-up reminder at the given ISO-8601 date-time.
     * Body: { "scheduledAt": "2026-04-20T09:00:00" }
     *
     * Validation (@NotNull, @Future) is handled by ScheduleFollowUpRequest,
     * replacing the previous manual null-check and date comparison.
     */
    @PostMapping("/{id}/schedule")
    public ResponseEntity<Map<String, String>> scheduleFollowUp(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable Long id,
            @Valid @RequestBody ScheduleFollowUpRequest body) {
        emailService.scheduleFollowUp(id, user.getUsername(), body.getScheduledAt());
        return ResponseEntity.ok(Map.of("message", "Follow-up scheduled for " + body.getScheduledAt()));
    }
}
