package com.nudge.service;

import com.nudge.model.FilterReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void classifyOpen_false_forGenuineHumanUserAgent() {
        String realUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
        assertThat(service.classifyOpen(realUa, "tracking-id-1", "client@example.com")).isNull();
    }

    @Test
    void classifyOpen_true_forKnownBotUserAgent() {
        assertThat(service.classifyOpen("Proofpoint/1.0", "tracking-id-2", "client@example.com")).isEqualTo(FilterReason.SCANNER);
    }

    @Test
    void classifyOpen_matchesKnownBotPatterns_caseInsensitively() {
        assertThat(service.classifyOpen("MIMECAST-SCANNER/2.1", "tracking-id-3", "client@example.com")).isEqualTo(FilterReason.SCANNER);
    }

    @Test
    void classifyOpen_false_forNullUserAgent_onFirstOpen() {
        assertThat(service.classifyOpen(null, "tracking-id-4", "client@example.com")).isNull();
    }

    @Test
    void classifyOpen_false_forBlankUserAgent_onFirstOpen() {
        assertThat(service.classifyOpen("  ", "tracking-id-5", "client@example.com")).isNull();
    }

    @Test
    void classifyOpen_firstOpen_isNeverFlaggedByTiming() {
        assertThat(service.classifyOpen("Mozilla/5.0 real client", "tracking-id-6", "client@example.com")).isNull();
    }

    @Test
    void classifyOpen_true_whenSecondOpenIsWithinPrefetchWindow() {
        String ua = "Mozilla/5.0 real client";
        String trackingId = "tracking-id-7";

        assertThat(service.classifyOpen(ua, trackingId, "client@example.com")).isNull(); // first open
        assertThat(service.classifyOpen(ua, trackingId, "client@example.com")).isEqualTo(FilterReason.RAPID_REFETCH);  // rapid re-fetch
    }

    @Test
    void classifyOpen_ratesIndependently_perTrackingId() {
        String ua = "Mozilla/5.0 real client";

        assertThat(service.classifyOpen(ua, "tracking-id-8", "client@example.com")).isNull();
        // A different tracking ID's first open must not be affected by another ID's timing.
        assertThat(service.classifyOpen(ua, "tracking-id-9", "client@example.com")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Mozilla/5.0 (Windows NT 5.1; rv:11.0) Gecko Firefox/11.0 (via ggpht.com GoogleImageProxy)",
        "YahooMailProxy; https://help.yahoo.com/kb/yahoo-mail-proxy-SLN28749.html",
        "Outlook-iOS/2.0",
        "OutlookAndroid/4.2"
    })
    void classifyOpen_countsMailClientsThatFetchWhenTheRecipientReads(String userAgent) {
        assertThat(service.classifyOpen(userAgent, "reader", "client@example.com")).isNull();
    }

    @Test
    void classifyOpen_appleMailPrivacyProtectionIsNotARead() {
        assertThat(service.classifyOpen("Mozilla/5.0", "apple", "client@example.com")).isEqualTo(FilterReason.APPLE_PRIVACY);
    }

    @Test
    void classifyOpen_filteredLoadDoesNotHideTheGenuineOpenThatFollows() {
        service.classifyOpen("Proofpoint", "sequence", "client@example.com");
        service.classifyOpen("Mozilla/5.0", "sequence", "client@example.com");
        assertThat(service.classifyOpen("Mozilla/5.0 real client", "sequence", "client@example.com")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@proton.me", "A@ProtonMail.com", "a@pm.me"})
    void classifyOpen_protonLoadsImagesOnReception(String recipient) {
        assertThat(service.classifyOpen("Mozilla/5.0 real client", "proton", recipient)).isEqualTo(FilterReason.PROTON_PRELOAD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@notproton.me", "a@proton.me.example.com", ""})
    void classifyOpen_otherDomainsAreNotTakenForProton(String recipient) {
        assertThat(service.classifyOpen("Mozilla/5.0 real client", "other" + recipient, recipient)).isNull();
    }

    @Test
    void classifyOpen_countsTheHeyProxyWhichFetchesWhenTheRecipientReads() {
        String hey = "hey.com/imageproxy Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
        assertThat(service.classifyOpen(hey, "hey", "a@hey.com")).isNull();
    }
}
