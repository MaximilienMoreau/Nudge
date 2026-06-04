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

    // ── Open-only behaviour ───────────────────────────────────────────────────

    @Test
    void score_isZero_whenNoEvents() {
        assertThat(service.computeScore(List.of())).isEqualTo(0);
    }

    @Test
    void score_isPositive_afterSingleRecentOpen() {
        // Use "just now" so recency = round(40 × e^0) = 40, total = 15 + 40 = 55
        TrackingEvent open = event(EventType.OPEN, LocalDateTime.now());
        assertThat(service.computeScore(List.of(open))).isEqualTo(55);
    }

    @Test
    void score_caps_at_100() {
        // 6 opens very recently → volume=40, recency≈40, frequency=20 → capped at 100
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
        // 2 opens from 5-6 days ago: recency ≈ 0 at that age (exponential decay),
        // volume = 30, frequency = 10 → total = 40
        List<TrackingEvent> twoOpens = List.of(
            event(EventType.OPEN, LocalDateTime.now().minusDays(5)),
            event(EventType.OPEN, LocalDateTime.now().minusDays(6))
        );
        assertThat(service.computeScore(twoOpens)).isEqualTo(40);
    }

    // ── Exponential decay ─────────────────────────────────────────────────────

    @Test
    void recencyScore_halfLifeIsApproximatelySixHours() {
        // At 0 h recency = 40; at the 6-hour half-life it should be ~20.
        // Use ±1 tolerance to absorb sub-second timing jitter in CI.
        TrackingEvent open = event(EventType.OPEN, LocalDateTime.now().minusHours(6));
        int score = service.computeScore(List.of(open));
        // volume(1) = 15, recency(6h) = round(40 × 0.5) = 20 → total = 35
        assertThat(score).isBetween(33, 37);
    }

    @Test
    void recencyScore_nearlyZero_forVeryOldOpen() {
        // 14 days ago: decay ≈ 40 × e^(−0.1155 × 336) ≈ 0
        TrackingEvent open = event(EventType.OPEN, LocalDateTime.now().minusDays(14));
        int score = service.computeScore(List.of(open));
        // volume(1) = 15, recency ≈ 0, frequency = 0 → total = 15
        assertThat(score).isEqualTo(15);
    }

    // ── Click bonus ───────────────────────────────────────────────────────────

    @Test
    void score_isNonZero_whenOnlyOneClick() {
        TrackingEvent click = event(EventType.CLICK, LocalDateTime.now().minusHours(1));
        assertThat(service.computeScore(List.of(click))).isEqualTo(10);
    }

    @Test
    void score_clickBonus_capsAtTwentyForTwoOrMoreClicks() {
        List<TrackingEvent> twoClicks = List.of(
            event(EventType.CLICK, LocalDateTime.now().minusMinutes(5)),
            event(EventType.CLICK, LocalDateTime.now().minusMinutes(10))
        );
        assertThat(service.computeScore(twoClicks)).isEqualTo(20);
    }

    @Test
    void score_clickBonus_addedOnTopOfOpenScore() {
        // 1 open "just now" (recency = 40) + 1 click → 15 + 40 + 0 + 10 = 65
        List<TrackingEvent> events = List.of(
            event(EventType.OPEN,  LocalDateTime.now()),
            event(EventType.CLICK, LocalDateTime.now().minusMinutes(5))
        );
        assertThat(service.computeScore(events)).isEqualTo(65);
    }

    // ── Bot filtering ─────────────────────────────────────────────────────────

    @Test
    void botEvents_areExcludedFromScore() {
        // One bot open + one genuine open (just now)
        // Score should reflect only the genuine open: 15 + 40 = 55
        TrackingEvent botOpen     = event(EventType.OPEN, LocalDateTime.now().minusMinutes(1));
        botOpen.setSuspectedBot(true);
        TrackingEvent genuineOpen = event(EventType.OPEN, LocalDateTime.now());

        assertThat(service.computeScore(List.of(botOpen, genuineOpen))).isEqualTo(55);
    }

    @Test
    void score_isZero_whenAllEventsAreBots() {
        TrackingEvent botOpen = event(EventType.OPEN, LocalDateTime.now());
        botOpen.setSuspectedBot(true);

        assertThat(service.computeScore(List.of(botOpen))).isEqualTo(0);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TrackingEvent event(EventType type, LocalDateTime timestamp) {
        TrackingEvent e = new TrackingEvent();
        e.setType(type);
        e.setTimestamp(timestamp);
        return e;
    }
}
