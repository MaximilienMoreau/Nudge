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

    // ── Open-only behaviour (unchanged) ──────────────────────────────────────

    @Test
    void score_isZero_whenNoEvents() {
        assertThat(service.computeScore(List.of())).isEqualTo(0);
    }

    @Test
    void score_isPositive_afterSingleRecentOpen() {
        TrackingEvent open = event(EventType.OPEN, LocalDateTime.now().minusMinutes(30));
        int score = service.computeScore(List.of(open));
        // 1 open → 15 pts volume + 40 pts recency (< 1h) + 0 frequency + 0 click = 55
        assertThat(score).isEqualTo(55);
    }

    @Test
    void score_caps_at_100() {
        // 6 opens very recently → volume=40, recency=40, frequency=20 → 100
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
        // 2 opens old → volume=30, recency=10 (< 7d), frequency=10 → 50
        List<TrackingEvent> twoOpens = List.of(
            event(EventType.OPEN, LocalDateTime.now().minusDays(5)),
            event(EventType.OPEN, LocalDateTime.now().minusDays(6))
        );
        assertThat(service.computeScore(twoOpens)).isEqualTo(50);
    }

    // ── Click bonus ───────────────────────────────────────────────────────────

    @Test
    void score_isNonZero_whenOnlyOneClick() {
        // Clicks alone give the click bonus; recency/volume/frequency = 0
        TrackingEvent click = event(EventType.CLICK, LocalDateTime.now().minusHours(1));
        // clickBonus(1) = 10
        assertThat(service.computeScore(List.of(click))).isEqualTo(10);
    }

    @Test
    void score_clickBonus_capsAtTwentyForTwoOrMoreClicks() {
        List<TrackingEvent> twoClicks = List.of(
            event(EventType.CLICK, LocalDateTime.now().minusMinutes(5)),
            event(EventType.CLICK, LocalDateTime.now().minusMinutes(10))
        );
        // clickBonus(2) = 20; no opens → total = 20
        assertThat(service.computeScore(twoClicks)).isEqualTo(20);
    }

    @Test
    void score_clickBonus_addedOnTopOfOpenScore() {
        // 1 recent open (55 pts) + 1 click (10 pts) = 65
        List<TrackingEvent> events = List.of(
            event(EventType.OPEN,  LocalDateTime.now().minusMinutes(30)),
            event(EventType.CLICK, LocalDateTime.now().minusMinutes(20))
        );
        assertThat(service.computeScore(events)).isEqualTo(65);
    }

    // ── Scalar overload (hot-path) ────────────────────────────────────────────

    @Test
    void scalarOverload_matchesListOverload_forOpensOnly() {
        LocalDateTime lastOpen = LocalDateTime.now().minusMinutes(30);
        List<TrackingEvent> events = List.of(event(EventType.OPEN, lastOpen));

        int fromList   = service.computeScore(events);
        int fromScalar = service.computeScore(1L, 0L, lastOpen);

        assertThat(fromList).isEqualTo(fromScalar);
    }

    @Test
    void scalarOverload_isZero_whenCountsAreZero() {
        assertThat(service.computeScore(0L, 0L, null)).isEqualTo(0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TrackingEvent event(EventType type, LocalDateTime timestamp) {
        TrackingEvent e = new TrackingEvent();
        e.setType(type);
        e.setTimestamp(timestamp);
        return e;
    }
}
