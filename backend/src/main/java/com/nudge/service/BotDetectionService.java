package com.nudge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects bot/proxy opens to prevent inflated lead scores.
 *
 * Two complementary heuristics run in order:
 *
 *   1. User-Agent pattern match — identifies known mail scanners, security
 *      gateways, and image proxies (Apple MPP, Google Image Proxy, MS Exchange
 *      Safe Links, Proofpoint, Mimecast, etc.) that pre-fetch tracking pixels
 *      before the human ever opens the email.
 *
 *   2. Rapid-succession timing — two opens on the same tracking ID within
 *      PREFETCH_WINDOW_MS are almost always a server-side pre-fetch cycle.
 *      The first open is never flagged; only subsequent ones within the window.
 *
 * Flagged events are persisted with suspectedBot = true and excluded from
 * lead-score computation and real-time notifications.
 */
@Service
public class BotDetectionService {

    private static final Logger log = LoggerFactory.getLogger(BotDetectionService.class);

    /** Two opens within this window → second one is a suspected pre-fetch. */
    static final long PREFETCH_WINDOW_MS = 30_000L;

    private static final List<String> BOT_UA_PATTERNS = List.of(
        "googleimageproxy",
        "googlebot",
        "bingbot",
        "outlook-ios",
        "outlookandroid",
        "mimecast",
        "proofpoint",
        "barracuda",
        "microsoft-exchange",
        "previewsapp",
        "safelinks",
        "urlscan",
        "apple-pubsub"
    );

    /** trackingId → ms timestamp of most recent open */
    private final Map<String, Long> lastOpenTs = new ConcurrentHashMap<>();

    /**
     * Returns true when the open looks like a bot/proxy load.
     *
     * @param userAgent   User-Agent header value (may be null)
     * @param trackingId  UUID identifying the tracked email
     */
    public boolean isSuspectedBot(String userAgent, String trackingId) {
        if (matchesKnownBot(userAgent)) {
            log.debug("Bot UA detected for trackingId={}: ua={}", trackingId, userAgent);
            return true;
        }
        if (isRapidSuccession(trackingId)) {
            log.debug("Rapid-succession open detected for trackingId={}", trackingId);
            return true;
        }
        return false;
    }

    private boolean matchesKnownBot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return false;
        String ua = userAgent.toLowerCase();
        for (String pattern : BOT_UA_PATTERNS) {
            if (ua.contains(pattern)) return true;
        }
        return false;
    }

    /**
     * Records this open's timestamp and returns true if a previous open
     * occurred within PREFETCH_WINDOW_MS. The first open for any ID is
     * never flagged — only rapid successors are.
     */
    private boolean isRapidSuccession(String trackingId) {
        long now = System.currentTimeMillis();
        Long prev = lastOpenTs.put(trackingId, now);
        return prev != null && (now - prev) < PREFETCH_WINDOW_MS;
    }

    /** Purge stale entries every 10 minutes to bound memory usage. */
    @Scheduled(fixedDelay = 600_000)
    void evictStaleEntries() {
        long cutoff = System.currentTimeMillis() - PREFETCH_WINDOW_MS * 20;
        int before = lastOpenTs.size();
        lastOpenTs.entrySet().removeIf(e -> e.getValue() < cutoff);
        int removed = before - lastOpenTs.size();
        if (removed > 0) log.debug("BotDetectionService evicted {} stale entries", removed);
    }
}
