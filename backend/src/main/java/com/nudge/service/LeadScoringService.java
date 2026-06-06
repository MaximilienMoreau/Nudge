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
 *  - Recency        : up to 40 pts — continuous exponential decay with a
 *                     6-hour half-life (score = 40 × e^(−λ × hoursAgo),
 *                     λ = ln 2 / 6). Avoids the abrupt step-function jumps
 *                     of a bucket-based approach.
 *  - Frequency bonus: up to 20 pts (multiple opens = high interest)
 *  - Click bonus    : up to 20 pts (link clicks = stronger intent signal)
 *
 *  Bot events (suspectedBot = true) are excluded before any computation
 *  so that Apple MPP / mail-scanner pre-fetches don't inflate scores.
 *
 * EventType is compared by identity (==) everywhere — no .name().equals().
 *    computeScore passes over the event list once to collect all metrics.
 */
@Service
public class LeadScoringService {

    /** Half-life of the recency signal in hours. Score halves every 6 h. */
    private static final double HALF_LIFE_HOURS = 6.0;
    private static final double LAMBDA = Math.log(2) / HALF_LIFE_HOURS;

    /**
     * Compute the lead score from a list of tracking events.
     *
     * BD:  Suspected-bot events are skipped before aggregation.
     *  Single-pass accumulation — one loop collects openCount, clickCount,
     *      and lastOpen; delegates to the scalar overload for the formula.
     *
     * @param events all events for the email (may be empty)
     * @return score in [0, 100]
     */
    public int computeScore(List<TrackingEvent> events) {
        long openCount  = 0;
        long clickCount = 0;
        LocalDateTime lastOpen = null;

        for (TrackingEvent e : events) {
            if (e.isSuspectedBot()) continue;              // BD: skip bot events
            if (e.getType() == EventType.OPEN) {           // enum identity
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
     * Called with pre-aggregated counts (genuine opens only) and the timestamp
     * of the most recent genuine open.
     *
     * @param openCount  genuine OPEN event count (suspected bots excluded)
     * @param clickCount total CLICK event count (clicks are always genuine)
     * @param lastOpen   timestamp of the most recent genuine OPEN, or null
     * @return score in [0, 100]
     */
    public int computeScore(long openCount, long clickCount, LocalDateTime lastOpen) {
        if (openCount == 0 && clickCount == 0) return 0;

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
     * Continuous exponential decay: score = 40 × e^(−λ × hoursAgo)
     * where λ = ln(2) / 6, giving a 6-hour half-life.
     *
     * Examples:
     *   0 h  → 40 pts   (just opened)
     *   6 h  → 20 pts   (half-life)
     *  12 h  → 10 pts
     *  24 h  →  2–3 pts
     *  48 h+ →  0 pts
     *
     * This replaces the previous step-function (binary buckets) to avoid
     * a score of 40 pts at 59 min dropping abruptly to 30 pts at 61 min.
     */
    private int recencyScore(LocalDateTime lastOpen) {
        if (lastOpen == null) return 0;
        double hoursAgo = ChronoUnit.MINUTES.between(lastOpen, LocalDateTime.now()) / 60.0;
        if (hoursAgo < 0) hoursAgo = 0; // clock skew guard
        return (int) Math.round(40.0 * Math.exp(-LAMBDA * hoursAgo));
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
