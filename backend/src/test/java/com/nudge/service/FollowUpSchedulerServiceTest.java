package com.nudge.service;

import com.nudge.model.TrackedEmail;
import com.nudge.model.User;
import com.nudge.repository.TrackedEmailRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpSchedulerServiceTest {
    private final TrackedEmailRepository repo = mock(TrackedEmailRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final EmailNotificationService mail = mock(EmailNotificationService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private FollowUpSchedulerService scheduler() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new FollowUpSchedulerService(repo, notifications, mail, transactions);
    }

    private TrackedEmail email(long id) {
        User owner = new User();
        owner.setEmail("owner@example.com");
        TrackedEmail email = new TrackedEmail();
        email.setId(id);
        email.setUser(owner);
        email.setSubject("Reminder");
        email.setRecipientEmail("recipient@example.com");
        email.setScheduledFollowUpAt(LocalDateTime.now().minusHours(1));
        return email;
    }

    @Test
    void advancesByIdWhenProcessedRemindersDisappearFromResults() {
        TrackedEmail first = email(100);
        TrackedEmail second = email(101);
        when(repo.findByScheduledFollowUpAtLessThanEqualAndArchivedAtIsNullAndIdGreaterThanOrderByIdAsc(
                any(), eq(0L), any())).thenReturn(new SliceImpl<>(List.of(first), PageRequest.of(0, 100), true));
        when(repo.findByScheduledFollowUpAtLessThanEqualAndArchivedAtIsNullAndIdGreaterThanOrderByIdAsc(
                any(), eq(100L), any())).thenReturn(new SliceImpl<>(List.of(second)));
        when(repo.findById(100L)).thenReturn(Optional.of(first));
        when(repo.findById(101L)).thenReturn(Optional.of(second));

        scheduler().checkDueFollowUps();

        verify(notifications, times(2)).notifyUser(eq("owner@example.com"), any());
        assertThat(first.getScheduledFollowUpAt()).isNull();
        assertThat(second.getScheduledFollowUpAt()).isNull();
    }

    @Test
    void preservesConcurrentReschedulingAndOtherEdits() {
        TrackedEmail snapshot = email(1);
        TrackedEmail current = email(1);
        LocalDateTime newSchedule = LocalDateTime.now().plusDays(1);
        current.setScheduledFollowUpAt(newSchedule);
        when(repo.findByScheduledFollowUpAtLessThanEqualAndArchivedAtIsNullAndIdGreaterThanOrderByIdAsc(
                any(), eq(0L), any())).thenReturn(new SliceImpl<>(List.of(snapshot)));
        when(repo.findById(1L)).thenReturn(Optional.of(current));

        scheduler().checkDueFollowUps();

        assertThat(current.getScheduledFollowUpAt()).isEqualTo(newSchedule);
        verify(repo, never()).save(any());
    }

    @Test
    void failedNotificationDoesNotClearReminderOrPreventNextBatch() {
        TrackedEmail first = email(1);
        TrackedEmail second = email(2);
        when(repo.findByScheduledFollowUpAtLessThanEqualAndArchivedAtIsNullAndIdGreaterThanOrderByIdAsc(
                any(), eq(0L), any())).thenReturn(new SliceImpl<>(List.of(first), PageRequest.of(0, 100), true));
        when(repo.findByScheduledFollowUpAtLessThanEqualAndArchivedAtIsNullAndIdGreaterThanOrderByIdAsc(
                any(), eq(1L), any())).thenReturn(new SliceImpl<>(List.of(second)));
        doThrow(new IllegalStateException("Unavailable")).doNothing()
                .when(notifications).notifyUser(anyString(), any());
        when(repo.findById(2L)).thenReturn(Optional.of(second));

        scheduler().checkDueFollowUps();

        assertThat(first.getScheduledFollowUpAt()).isNotNull();
        assertThat(second.getScheduledFollowUpAt()).isNull();
    }
    @Test
    void smtpFailureLeavesReminderPending() {
        TrackedEmail first = email(1);
        when(repo.findByScheduledFollowUpAtLessThanEqualAndArchivedAtIsNullAndIdGreaterThanOrderByIdAsc(
                any(), eq(0L), any())).thenReturn(new SliceImpl<>(List.of(first)));
        doThrow(new IllegalStateException("SMTP failed")).when(mail).sendFollowUpReminder(anyString(), anyString(), anyString());
        scheduler().checkDueFollowUps();
        assertThat(first.getScheduledFollowUpAt()).isNotNull();
        verify(repo, never()).save(any());
    }

}
