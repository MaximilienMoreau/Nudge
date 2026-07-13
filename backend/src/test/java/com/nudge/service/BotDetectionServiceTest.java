package com.nudge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BotDetectionService (UA pattern matching + rapid-succession heuristic).
 * No Spring context needed — pure logic, no scheduled eviction triggered.
 */
class BotDetectionServiceTest {

    private BotDetectionService service;

    @BeforeEach
    void setUp() {
        service = new BotDetectionService();
    }

    @Test
    void isSuspectedBot_false_forGenuineHumanUserAgent() {
        String realUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
        assertThat(service.isSuspectedBot(realUa, "tracking-id-1")).isFalse();
    }

    @Test
    void isSuspectedBot_true_forKnownBotUserAgent() {
        assertThat(service.isSuspectedBot("GoogleImageProxy/1.0", "tracking-id-2")).isTrue();
    }

    @Test
    void isSuspectedBot_matchesKnownBotPatterns_caseInsensitively() {
        assertThat(service.isSuspectedBot("MIMECAST-SCANNER/2.1", "tracking-id-3")).isTrue();
    }

    @Test
    void isSuspectedBot_false_forNullUserAgent_onFirstOpen() {
        assertThat(service.isSuspectedBot(null, "tracking-id-4")).isFalse();
    }

    @Test
    void isSuspectedBot_false_forBlankUserAgent_onFirstOpen() {
        assertThat(service.isSuspectedBot("  ", "tracking-id-5")).isFalse();
    }

    @Test
    void isSuspectedBot_firstOpen_isNeverFlaggedByTiming() {
        assertThat(service.isSuspectedBot("Mozilla/5.0 real client", "tracking-id-6")).isFalse();
    }

    @Test
    void isSuspectedBot_true_whenSecondOpenIsWithinPrefetchWindow() {
        String ua = "Mozilla/5.0 real client";
        String trackingId = "tracking-id-7";

        assertThat(service.isSuspectedBot(ua, trackingId)).isFalse(); // first open
        assertThat(service.isSuspectedBot(ua, trackingId)).isTrue();  // rapid re-fetch
    }

    @Test
    void isSuspectedBot_ratesIndependently_perTrackingId() {
        String ua = "Mozilla/5.0 real client";

        assertThat(service.isSuspectedBot(ua, "tracking-id-8")).isFalse();
        // A different tracking ID's first open must not be affected by another ID's timing.
        assertThat(service.isSuspectedBot(ua, "tracking-id-9")).isFalse();
    }
}
