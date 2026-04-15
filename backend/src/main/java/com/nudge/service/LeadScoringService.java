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
 *
 * Q1/Q8: EventType is compared by identity (==) everywhere — no .name().equals().
 * P3:    computeScore passes over the event list once to collect all metrics.
 */
@Service
public class LeadScoringService {

    /**
     * Compute the lead score from a list of tracking events.
     * P3: Single-pass accumulation — one loop collects openCount and lastOpen.
     *
     * @param events all events for the email (may be empty)
     * @return score in [0, 100]
     */
    public int computeScore(List<TrackingEvent> events) {
        long openCount = 0;
        LocalDateTime lastOpen = null;

        // P3: one pass over the list collects both metrics
        for (TrackingEvent e : events) {
            if (e.getType() == EventType.OPEN) {   // Q1/Q8: enum identity comparison
                openCount++;
                if (lastOpen == null || e.getTimestamp().isAfter(lastOpen)) {
                    lastOpen = e.getTimestamp();
                }
            }
        }

        if (openCount == 0) return 0;

        int volumeScore    = volumeScore(openCount);
        int recencyScore   = recencyScore(lastOpen);
        int frequencyBonus = frequencyBonus(openCount);

        return Math.min(volumeScore + recencyScore + frequencyBonus, 100);
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
}
