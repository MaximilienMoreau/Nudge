package com.nudge.service;

import com.nudge.dto.EmailCreateRequest;
import com.nudge.dto.EmailDTO;
import com.nudge.model.TrackedEmail;
import com.nudge.model.User;
import com.nudge.repository.TrackedEmailRepository;
import com.nudge.repository.TrackingEventRepository;
import com.nudge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailService, focused on IDOR protection (findAndVerify)
 * and the archive/restore/permanent-delete lifecycle.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock TrackedEmailRepository emailRepo;
    @Mock TrackingEventRepository eventRepo;
    @Mock UserRepository userRepo;
    @Mock LeadScoringService leadScoringService;
    @Mock EncryptionService encryptionService;

    EmailService emailService;

    User owner;
    User attacker;
    TrackedEmail ownedEmail;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(emailRepo, eventRepo, userRepo, leadScoringService, encryptionService);
        ReflectionTestUtils.setField(emailService, "baseUrl", "http://localhost:8080");

        owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.com");

        attacker = new User();
        attacker.setId(2L);
        attacker.setEmail("attacker@example.com");

        ownedEmail = new TrackedEmail();
        ownedEmail.setId(100L);
        ownedEmail.setUser(owner);
        ownedEmail.setSubject("Q3 proposal");
        ownedEmail.setContent("encrypted-blob");
        ownedEmail.setRecipientEmail("client@example.com");
        ownedEmail.setTrackingId("tracking-uuid-1");
    }

    // ── IDOR protection ─────────────────────────────────────────────────────

    @Test
    void getEmailById_throwsSecurityException_whenRequestedByNonOwner() {
        when(emailRepo.findById(100L)).thenReturn(Optional.of(ownedEmail));

        assertThatThrownBy(() -> emailService.getEmailById(100L, attacker.getEmail()))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void archiveEmail_throwsSecurityException_whenRequestedByNonOwner() {
        when(emailRepo.findById(100L)).thenReturn(Optional.of(ownedEmail));

        assertThatThrownBy(() -> emailService.archiveEmail(100L, attacker.getEmail()))
            .isInstanceOf(SecurityException.class);

        verify(emailRepo, never()).save(any());
    }

    @Test
    void permanentlyDeleteEmail_throwsSecurityException_whenRequestedByNonOwner() {
        when(emailRepo.findById(100L)).thenReturn(Optional.of(ownedEmail));

        assertThatThrownBy(() -> emailService.permanentlyDeleteEmail(100L, attacker.getEmail()))
            .isInstanceOf(SecurityException.class);

        verify(emailRepo, never()).delete(any());
    }

    @Test
    void getEmailById_throwsIllegalArgument_whenEmailDoesNotExist() {
        when(emailRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> emailService.getEmailById(999L, owner.getEmail()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getEmailById_succeeds_forOwner() {
        when(emailRepo.findById(100L)).thenReturn(Optional.of(ownedEmail));
        when(eventRepo.findByEmailOrderByTimestampDesc(ownedEmail)).thenReturn(List.of());
        when(encryptionService.decrypt("encrypted-blob")).thenReturn("plaintext body");
        when(leadScoringService.computeScore(anyList())).thenReturn(0);

        EmailDTO dto = emailService.getEmailById(100L, owner.getEmail());

        assertThat(dto.getId()).isEqualTo(100L);
        assertThat(dto.getContent()).isEqualTo("plaintext body");
    }

    // ── Archive / restore / permanent delete lifecycle ──────────────────────

    @Test
    void archiveEmail_setsArchivedAt_forOwner() {
        when(emailRepo.findById(100L)).thenReturn(Optional.of(ownedEmail));

        emailService.archiveEmail(100L, owner.getEmail());

        assertThat(ownedEmail.getArchivedAt()).isNotNull();
        verify(emailRepo).save(ownedEmail);
    }

    @Test
    void restoreEmail_clearsArchivedAt_forOwner() {
        ownedEmail.setArchivedAt(java.time.LocalDateTime.now());
        when(emailRepo.findById(100L)).thenReturn(Optional.of(ownedEmail));

        emailService.restoreEmail(100L, owner.getEmail());

        assertThat(ownedEmail.getArchivedAt()).isNull();
        verify(emailRepo).save(ownedEmail);
    }

    @Test
    void permanentlyDeleteEmail_deletesRow_forOwner() {
        when(emailRepo.findById(100L)).thenReturn(Optional.of(ownedEmail));

        emailService.permanentlyDeleteEmail(100L, owner.getEmail());

        verify(emailRepo).delete(ownedEmail);
    }

    // ── createTrackedEmail ───────────────────────────────────────────────────

    @Test
    void createTrackedEmail_createsOneEmailPerRecipient() {
        when(userRepo.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(encryptionService.encrypt(any())).thenReturn("encrypted-blob");
        when(leadScoringService.computeScore(anyList())).thenReturn(0);
        when(emailRepo.save(any(TrackedEmail.class))).thenAnswer(inv -> inv.getArgument(0));

        EmailCreateRequest request = new EmailCreateRequest();
        request.setSubject("Multi-recipient blast");
        request.setContent("body");
        request.setRecipientEmails(List.of("a@example.com", "b@example.com"));

        List<EmailDTO> created = emailService.createTrackedEmail(owner.getEmail(), request);

        assertThat(created).hasSize(2);
        assertThat(created).extracting(EmailDTO::getRecipientEmail)
                .containsExactlyInAnyOrder("a@example.com", "b@example.com");
        verify(emailRepo, times(2)).save(any(TrackedEmail.class));
    }

    @Test
    void createTrackedEmail_generatesDistinctTrackingIdsPerRecipient() {
        when(userRepo.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(encryptionService.encrypt(any())).thenReturn("encrypted-blob");
        when(leadScoringService.computeScore(anyList())).thenReturn(0);
        when(emailRepo.save(any(TrackedEmail.class))).thenAnswer(inv -> inv.getArgument(0));

        EmailCreateRequest request = new EmailCreateRequest();
        request.setSubject("Multi-recipient blast");
        request.setContent("body");
        request.setRecipientEmails(List.of("a@example.com", "b@example.com"));

        List<EmailDTO> created = emailService.createTrackedEmail(owner.getEmail(), request);

        assertThat(created.get(0).getTrackingId()).isNotEqualTo(created.get(1).getTrackingId());
    }
}
