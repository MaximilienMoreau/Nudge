package com.nudge.service;

import com.nudge.model.FilterReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a pixel load can be counted as a read.
 *
 * Image proxies that fetch when the recipient displays the email (Gmail's
 * GoogleImageProxy, YahooMailProxy) and mail apps (Outlook mobile) are reads:
 * filtering them would make those recipients look as if they never read anything.
 *
 * Not counted:
 *   1. Scanners and security gateways, identified by User-Agent.
 *   2. Apple Mail Privacy Protection, whose fetches do not depend on the recipient
 *      reading. It only announces itself with a bare "Mozilla/5.0".
 *   3. Emails sent to a Proton address, whose images are loaded on reception.
 *   4. A second load of the same pixel within PREFETCH_WINDOW_MS. The first load
 *      is never flagged by this rule.
 *
 * Filtered events are persisted and excluded from scores and notifications.
 */
@Service
public class BotDetectionService {

    private static final Logger log = LoggerFactory.getLogger(BotDetectionService.class);

    /** Two opens within this window → second one is a suspected pre-fetch. */
    static final long PREFETCH_WINDOW_MS = 30_000L;

    private static final List<String> BOT_UA_PATTERNS = List.of(
        "googlebot",
        "bingbot",
        "mimecast",
        "proofpoint",
        "barracuda",
        "microsoft-exchange",
        "previewsapp",
        "safelinks",
        "urlscan",
        "apple-pubsub"
    );

    private static final Set<String> PROTON_DOMAINS =
        Set.of("proton.me", "protonmail.com", "protonmail.ch", "pm.me");

    /** trackingId → ms timestamp of most recent open */
    private final Map<String, Long> lastOpenTs = new ConcurrentHashMap<>();

    /**
     * Returns why an open must not be counted as a read, or null when it counts.
     *
     * @param userAgent   User-Agent header value (may be null)
     * @param trackingId  UUID identifying the tracked email
     * @param recipient   address the email was sent to (may be null)
     */
    public FilterReason classifyOpen(String userAgent, String trackingId, String recipient) {
        if (matchesKnownBot(userAgent))       return FilterReason.SCANNER;
        if (isApplePrivacyProxy(userAgent))   return FilterReason.APPLE_PRIVACY;
        if (isProtonMailbox(recipient))       return FilterReason.PROTON_PRELOAD;
        // Checked last so that filtered loads never hide a genuine open that follows.
        if (isRapidSuccession(trackingId))    return FilterReason.RAPID_REFETCH;
        return null;
    }

    /**
     * Proton loads the images of incoming emails on reception, through a proxy with no
     * documented User-Agent, so the recipient's domain is the only clue. Custom domains
     * hosted by Proton cannot be recognised.
     */
    private static boolean isProtonMailbox(String recipient) {
        if (recipient == null) return false;
        String domain = recipient.substring(recipient.lastIndexOf('@') + 1).trim().toLowerCase(java.util.Locale.ROOT);
        return PROTON_DOMAINS.contains(domain);
    }

    private static boolean isApplePrivacyProxy(String userAgent) {
        return userAgent != null && userAgent.trim().equals("Mozilla/5.0");
    }

    public boolean matchesKnownBot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return false;
        String ua = userAgent.toLowerCase(java.util.Locale.ROOT);
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
