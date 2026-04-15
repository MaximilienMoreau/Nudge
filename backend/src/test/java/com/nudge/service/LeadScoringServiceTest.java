package com.nudge.service;

import com.nudge.model.EventType;
import com.nudge.model.TrackingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for LeadScoringService.
 * No Spring context needed — pure business logic.
 */
class LeadScoringServiceTest {

    private LeadScoringService service;

    @BeforeEach
    void setUp() {
        service = new LeadScoringService();
    }

    @Test
    void score_isZero_whenNoEvents() {
        assertThat(service.computeScore(List.of())).isEqualTo(0);
    }

    @Test
    void score_isZero_whenOnlyClickEvents() {
        TrackingEvent click = event(EventType.CLICK, LocalDateTime.now().minusHours(1));
        assertThat(service.computeScore(List.of(click))).isEqualTo(0);
    }

    @Test
    void score_isPositive_afterSingleRecentOpen() {
        TrackingEvent open = event(EventType.OPEN, LocalDateTime.now().minusMinutes(30));
        int score = service.computeScore(List.of(open));
        // 1 open → 15 pts volume + 40 pts recency (< 1h) + 0 frequency = 55
        assertThat(score).isEqualTo(55);
    }

    @Test
    void score_caps_at_100() {
        // 3 opens very recently → volume=40, recency=40, frequency=15 → 95
        // 6 opens very recently → volume=40, recency=40, frequency=20 → capped at 100
        List<TrackingEvent> manyOpens = List.of(
            event(EventType.OPEN, LocalDateTime.now().minusMinutes(5)),
            event(EventType.OPEN, LocalDateTime.now().minusMinutes(10)),
            event(EventType.OPEN, LocalDateTime.now().minusMinutes(15)),
            event(EventType.OPEN, LocalDateTime.now().minusMinutes(20)),
            event(EventType.OPEN, LocalDateTime.now().minusMinutes(25)),
            event(EventType.OPEN, LocalDateTime.now().minusMinutes(30))
        );
        assertThat(service.computeScore(manyOpens)).isEqualTo(100);
    }

    @Test
    void score_decreases_for_older_opens() {
        TrackingEvent recentOpen = event(EventType.OPEN, LocalDateTime.now().minusMinutes(30));
        TrackingEvent oldOpen    = event(EventType.OPEN, LocalDateTime.now().minusDays(10));

        int recentScore = service.computeScore(List.of(recentOpen));
        int oldScore    = service.computeScore(List.of(oldOpen));

        assertThat(recentScore).isGreaterThan(oldScore);
    }

    @Test
    void frequencyBonus_appliesCorrectly() {
        // 2 opens → frequency bonus 10
        List<TrackingEvent> twoOpens = List.of(
            event(EventType.OPEN, LocalDateTime.now().minusDays(5)),
            event(EventType.OPEN, LocalDateTime.now().minusDays(6))
        );
        int score = service.computeScore(twoOpens);
        // volume: min(2*15,40)=30, recency: 10 (< 7 days), frequency: 10 → 50
        assertThat(score).isEqualTo(50);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TrackingEvent event(EventType type, LocalDateTime timestamp) {
        TrackingEvent e = new TrackingEvent();
        e.setType(type);
        e.setTimestamp(timestamp);
        return e;
    }
}
