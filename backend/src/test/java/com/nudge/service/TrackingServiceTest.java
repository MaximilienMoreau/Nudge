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
 *  P4  — findByEmailOrderByTimestampDesc is NEVER called (replaced by COUNT + findFirst).
 *  S4  — X-Forwarded-For is ignored when direct connection is not from trusted proxy.
 *  BD  — Suspected-bot opens are persisted but do NOT trigger notifications.
 */
@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

    @Mock TrackedEmailRepository   emailRepo;
    @Mock TrackingEventRepository  eventRepo;
    @Mock LeadScoringService       leadScoringService;
    @Mock NotificationService      notificationService;
    @Mock BotDetectionService      botDetectionService;

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

        service = new TrackingService(emailRepo, eventRepo, leadScoringService,
                notificationService, botDetectionService, Set.of());
    }

    // ── recordOpen — happy path ───────────────────────────────────────────────

    @Test
    void recordOpen_returnsTrue_whenEmailFound() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(botDetectionService.isSuspectedBot(any(), any())).thenReturn(false);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.OPEN)).thenReturn(1L);
        when(eventRepo.countByEmailAndType(email, EventType.CLICK)).thenReturn(0L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(55);

        assertThat(service.recordOpen("test-uuid", new MockHttpServletRequest())).isTrue();
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
        when(botDetectionService.isSuspectedBot(any(), any())).thenReturn(false);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndTypeAndSuspectedBotFalse(any(), any())).thenReturn(1L);
        when(eventRepo.countByEmailAndType(any(), eq(EventType.CLICK))).thenReturn(0L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(42);

        service.recordOpen("test-uuid", new MockHttpServletRequest());

        // P4: full event list must NEVER be loaded
        verify(eventRepo, never()).findByEmailOrderByTimestampDesc(any());
        verify(eventRepo, atLeastOnce()).countByEmailAndTypeAndSuspectedBotFalse(eq(email), any());
    }

    @Test
    void recordOpen_firesNotification_withOpenedType() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(botDetectionService.isSuspectedBot(any(), any())).thenReturn(false);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.OPEN)).thenReturn(2L);
        when(eventRepo.countByEmailAndType(email, EventType.CLICK)).thenReturn(0L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(70);

        service.recordOpen("test-uuid", new MockHttpServletRequest());

        ArgumentCaptor<NotificationDTO> captor = ArgumentCaptor.forClass(NotificationDTO.class);
        verify(notificationService).notifyUser(eq("owner@example.com"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("EMAIL_OPENED");
        assertThat(captor.getValue().getOpenCount()).isEqualTo(2);
    }

    // ── BD: Bot detection ─────────────────────────────────────────────────────

    @Test
    void recordOpen_BD_persistsBotEventWithFlag() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(botDetectionService.isSuspectedBot(any(), any())).thenReturn(true);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.recordOpen("test-uuid", new MockHttpServletRequest());

        assertThat(result).isTrue();
        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(eventRepo).save(captor.capture());
        assertThat(captor.getValue().isSuspectedBot()).isTrue();
    }

    @Test
    void recordOpen_BD_doesNotFireNotification_forBotOpen() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(botDetectionService.isSuspectedBot(any(), any())).thenReturn(true);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordOpen("test-uuid", new MockHttpServletRequest());

        verify(notificationService, never()).notifyUser(any(), any());
    }

    @Test
    void recordOpen_BD_doesNotComputeScore_forBotOpen() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(botDetectionService.isSuspectedBot(any(), any())).thenReturn(true);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordOpen("test-uuid", new MockHttpServletRequest());

        // Score computation must be skipped entirely for bot events
        verify(leadScoringService, never()).computeScore(anyLong(), anyLong(), any());
    }

    // ── recordClick ───────────────────────────────────────────────────────────

    @Test
    void recordClick_returnsNonNull_whenEmailFound() {
        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndTypeAndSuspectedBotFalse(any(), eq(EventType.OPEN))).thenReturn(1L);
        when(eventRepo.countByEmailAndType(any(), eq(EventType.CLICK))).thenReturn(1L);
        when(eventRepo.findFirstByEmailAndTypeAndSuspectedBotFalseOrderByTimestampDesc(any(), any()))
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
        when(eventRepo.countByEmailAndTypeAndSuspectedBotFalse(any(), eq(EventType.OPEN))).thenReturn(0L);
        when(eventRepo.countByEmailAndType(any(), eq(EventType.CLICK))).thenReturn(0L);
        when(eventRepo.findFirstByEmailAndTypeAndSuspectedBotFalseOrderByTimestampDesc(any(), any()))
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
        when(eventRepo.countByEmailAndTypeAndSuspectedBotFalse(email, EventType.OPEN)).thenReturn(1L);
        when(eventRepo.countByEmailAndType(email, EventType.CLICK)).thenReturn(1L);
        when(eventRepo.findFirstByEmailAndTypeAndSuspectedBotFalseOrderByTimestampDesc(email, EventType.OPEN))
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
        request.addHeader("X-Forwarded-For", "10.0.0.1");

        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(botDetectionService.isSuspectedBot(any(), any())).thenReturn(false);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndTypeAndSuspectedBotFalse(any(), any())).thenReturn(1L);
        when(eventRepo.countByEmailAndType(any(), eq(EventType.CLICK))).thenReturn(0L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(0);

        service.recordOpen("test-uuid", request);

        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(eventRepo).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("1.2.3.4");
    }

    @Test
    void recordOpen_S4_usesXFF_whenFromTrustedProxy() {
        TrackingService serviceWithProxy = new TrackingService(
                emailRepo, eventRepo, leadScoringService, notificationService,
                botDetectionService, Set.of("10.0.0.0/8"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Forwarded-For", "9.9.9.9");

        when(emailRepo.findByTrackingId("test-uuid")).thenReturn(Optional.of(email));
        when(botDetectionService.isSuspectedBot(any(), any())).thenReturn(false);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.countByEmailAndTypeAndSuspectedBotFalse(any(), any())).thenReturn(1L);
        when(eventRepo.countByEmailAndType(any(), eq(EventType.CLICK))).thenReturn(0L);
        when(leadScoringService.computeScore(anyLong(), anyLong(), any())).thenReturn(0);

        serviceWithProxy.recordOpen("test-uuid", request);

        ArgumentCaptor<TrackingEvent> captor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(eventRepo).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("9.9.9.9");
    }
}
