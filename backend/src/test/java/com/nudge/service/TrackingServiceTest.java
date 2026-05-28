package com.nudge.service;

import com.nudge.dto.NotificationDTO;
import com.nudge.model.EventType;
import com.nudge.model.TrackedEmail;
import com.nudge.model.TrackingEvent;
import com.nudge.model.User;
import com.nudge.repository.TrackedEmailRepository;
import com.nudge.repository.TrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TrackingService.
 *
 * Key invariants verified:
 *  P4 — findByEmailOrderByTimestampDesc is NEVER called (replaced by COUNT + findFirst).
 *  S4 — X-Forwarded-For is ignored when direct connection is not from trusted proxy.
 */
@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

    @Mock TrackedEmailRepository   emailRepo;
    @Mock TrackingEventRepository  eventRepo;
    @Mock LeadScoringService       leadScoringService;
    @Mock NotificationService      notificationService;

    // Injected via @InjectMocks — trustedProxies field name matches constructor param
    private TrackingService service;

    private TrackedEmail email;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setEmail("owner@example.com");

        email = new TrackedEmail();
        email.setId(1L);
        email.setSubject("Hello");
        email.setRecipientEmail("recipient@example.com");
        email.setTrackingId("test-uuid");
        email.setUser(owner);

        // Empty trusted proxy set for most tests (XFF ignored)
        service = new TrackingService(emailRepo, eventRepo, leadScoringService,
                notificationService, Set.of());
    }

    // ── recordOpen ────────────────────────────────────────────────────────────

    @Test
    void recordOpen_returnsTrue_whenEmailFound() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndType(email, EventType.OPEN)).thenReturn(1L);
        when(eventRepo.countByEmailAndType(email, EventType.CLICK)).thenReturn(0L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(55);

        boolean result = service.recordOpen("test-uuid", new MockHttpServletRequest());

        assertThat(result).isTrue();
        verify(eventRepo).save(argThat(e -> e.getType() == EventType.OPEN));
    }

    @Test
    void recordOpen_returnsFalse_whenEmailNotFound() {
        when(emailRepo.findByTrackingId("unknown")).thenReturn(Optional.empty());

        assertThat(service.recordOpen("unknown", new MockHttpServletRequest())).isFalse();
        verify(eventRepo, never()).save(any());
    }

    @Test
    void recordOpen_P4_doesNotLoadAllEvents() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndType(any(), any())).thenReturn(1L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(42);

        service.recordOpen("test-uuid", new MockHttpServletRequest());

        // P4: full event list must NEVER be loaded
        verify(eventRepo, never()).findByEmailOrderByTimestampDesc(any());
        // COUNT queries should be used instead
        verify(eventRepo, atLeastOnce()).countByEmailAndType(eq(email), any());
    }

    @Test
    void recordOpen_firesNotification_withOpenedType() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndType(email, EventType.OPEN)).thenReturn(2L);
        when(eventRepo.countByEmailAndType(email, EventType.CLICK)).thenReturn(0L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(70);

        service.recordOpen("test-uuid", new MockHttpServletRequest());

        ArgumentCaptor<NotificationDTO> captor = ArgumentCaptor.forClass(NotificationDTO.class);
        verify(notificationService).notifyUser(eq("owner@example.com"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("EMAIL_OPENED");
        assertThat(captor.getValue().getOpenCount()).isEqualTo(2);
    }

    // ── recordClick ───────────────────────────────────────────────────────────

    @Test
    void recordClick_returnsNonNull_whenEmailFound() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndType(any(), any())).thenReturn(1L);
        when(eventRepo.findFirstByEmailAndTypeOrderByTimestampDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(30);

        assertThat(service.recordClick("test-uuid", new MockHttpServletRequest())).isNotNull();
        verify(eventRepo).save(argThat(e -> e.getType() == EventType.CLICK));
    }

    @Test
    void recordClick_returnsNull_whenEmailNotFound() {
        when(emailRepo.findByTrackingId("unknown")).thenReturn(Optional.empty());

        assertThat(service.recordClick("unknown", new MockHttpServletRequest())).isNull();
        verify(eventRepo, never()).save(any());
    }

    @Test
    void recordClick_P4_doesNotLoadAllEvents() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndType(any(), any())).thenReturn(0L);
        when(eventRepo.findFirstByEmailAndTypeOrderByTimestampDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(0);

        service.recordClick("test-uuid", new MockHttpServletRequest());

        verify(eventRepo, never()).findByEmailOrderByTimestampDesc(any());
    }

    @Test
    void recordClick_firesNotification_withClickedType() {
        TrackingEvent lastOpen = new TrackingEvent();
        lastOpen.setTimestamp(LocalDateTime.now().minusMinutes(5));

        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndType(email, EventType.OPEN)).thenReturn(1L);
        when(eventRepo.countByEmailAndType(email, EventType.CLICK)).thenReturn(1L);
        when(eventRepo.findFirstByEmailAndTypeOrderByTimestampDesc(email, EventType.OPEN))
                .thenReturn(Optional.of(lastOpen));
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(65);

        service.recordClick("test-uuid", new MockHttpServletRequest());

        ArgumentCaptor<NotificationDTO> captor = ArgumentCaptor.forClass(NotificationDTO.class);
        verify(notificationService).notifyUser(eq("owner@example.com"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("EMAIL_CLICKED");
    }

    // ── S4: IP extraction ─────────────────────────────────────────────────────

    @Test
    void recordOpen_S4_ignoresXFF_whenNotFromTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("1.2.3.4");
        request.addHeader("X-Forwarded-For", "10.0.0.1");   // spoofed

        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndType(any(), any())).thenReturn(1L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(0);

        service.recordOpen("test-uuid", request);

        // The saved event must record the real remote addr, not the spoofed XFF
        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(eventRepo).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("1.2.3.4");
    }

    @Test
    void recordOpen_S4_usesXFF_whenFromTrustedProxy() {
        // Create service with a trusted proxy
        TrackingService serviceWithProxy = new TrackingService(
                emailRepo, eventRepo, leadScoringService, notificationService,
                Set.of("10.0.0.0/8"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");             // trusted proxy
        request.addHeader("X-Forwarded-For", "9.9.9.9"); // real client behind proxy

        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndType(any(), any())).thenReturn(1L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(0);

        serviceWithProxy.recordOpen("test-uuid", request);

        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(eventRepo).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("9.9.9.9");
    }
}
