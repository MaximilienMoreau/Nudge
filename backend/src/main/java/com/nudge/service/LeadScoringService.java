package com.nudge.service;

import com.nudge.model.EventType;
import com.nudge.model.TrackingEvent;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Computes the "Reply Probability Score" (0–100) for a tracked email.
 *
 * Scoring breakdown (max 100):
 *  - Opens volume   : up to 40 pts (15 per open, capped)
 *  - Recency        : up to 40 pts (how recently was the last open?)
 *  - Frequency bonus: up to 20 pts (multiple opens = high interest)
 *  - Click bonus    : up to 20 pts (link clicks = stronger intent signal)
 *
 * Q1/Q8: EventType is compared by identity (==) everywhere — no .name().equals().
 * P3:    computeScore passes over the event list once to collect all metrics.
 */
@Service
public class LeadScoringService {

    /**
     * Compute the lead score from a list of tracking events.
     * P3: Single-pass accumulation — one loop collects openCount, clickCount,
     *     and lastOpen. Delegates to the scalar overload to keep logic in one place.
     *
     * @param events all events for the email (may be empty)
     * @return score in [0, 100]
     */
    public int computeScore(List<TrackingEvent> events) {
        long openCount  = 0;
        long clickCount = 0;
        LocalDateTime lastOpen = null;

        // P3: one pass collects all metrics
        for (TrackingEvent e : events) {
            if (e.getType() == EventType.OPEN) {      // Q1/Q8: enum identity comparison
                openCount++;
                if (lastOpen == null || e.getTimestamp().isAfter(lastOpen)) {
                    lastOpen = e.getTimestamp();
                }
            } else if (e.getType() == EventType.CLICK) {
                clickCount++;
            }
        }

        return computeScore(openCount, clickCount, lastOpen);
    }

    /**
     * Hot-path variant used by TrackingService to avoid loading all events from DB.
     * Called with pre-aggregated counts so only COUNT + findFirst queries are needed.
     *
     * @param openCount  total OPEN events for the email
     * @param clickCount total CLICK events for the email
     * @param lastOpen   timestamp of the most recent OPEN, or null if none
     * @return score in [0, 100]
     */
    public int computeScore(long openCount, long clickCount, LocalDateTime lastOpen) {
        if (openCount == 0 && clickCount == 0) return 0;

        // Opens with no opens yet — only click bonus applies
        if (openCount == 0) return Math.min(clickBonus(clickCount), 100);

        return Math.min(
                volumeScore(openCount)
                + recencyScore(lastOpen)
                + frequencyBonus(openCount)
                + clickBonus(clickCount),
                100);
    }

    /** More opens = higher score, capped at 40. */
    private int volumeScore(long opens) {
        return (int) Math.min(opens * 15, 40);
    }

    /**
     * Reward recency:
     *  < 1 hour  → 40 pts
     *  < 1 day   → 30 pts
     *  < 3 days  → 20 pts
     *  < 7 days  → 10 pts
     *  older     →  0 pts
     */
    private int recencyScore(LocalDateTime lastOpen) {
        if (lastOpen == null) return 0;
        long hoursAgo = ChronoUnit.HOURS.between(lastOpen, LocalDateTime.now());
        if (hoursAgo < 1)   return 40;
        if (hoursAgo < 24)  return 30;
        if (hoursAgo < 72)  return 20;
        if (hoursAgo < 168) return 10;
        return 0;
    }

    /**
     * Frequency bonus for repeated engagement:
     *  > 5 opens → 20 pts
     *  > 3 opens → 15 pts
     *  > 1 open  → 10 pts
     */
    private int frequencyBonus(long opens) {
        if (opens > 5) return 20;
        if (opens > 3) return 15;
        if (opens > 1) return 10;
        return 0;
    }

    /**
     * Click bonus — each click signals stronger intent than a passive open:
     *  ≥ 2 clicks → 20 pts
     *  ≥ 1 click  → 10 pts
     */
    private int clickBonus(long clicks) {
        if (clicks >= 2) return 20;
        if (clicks >= 1) return 10;
        return 0;
    }
}
