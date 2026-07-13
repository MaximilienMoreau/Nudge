package com.nudge.repository;

import com.nudge.model.EventType;
import com.nudge.model.TrackedEmail;
import com.nudge.model.TrackingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackingEventRepository extends JpaRepository<TrackingEvent, Long> {

    /** All events for a given email, ordered by time. */
    List<TrackingEvent> findByEmailOrderByTimestampDesc(TrackedEmail email);

    /**
     * Batch-fetch all events for a list of emails in one query.
     * Eliminates the N+1 problem in EmailService.getEmailsForUser.
     */
    @Query("SELECT e FROM TrackingEvent e WHERE e.email IN :emails ORDER BY e.timestamp DESC")
    List<TrackingEvent> findByEmailInOrderByTimestampDesc(@Param("emails") List<TrackedEmail> emails);

    /** Count events of a specific type for an email. */
    long countByEmailAndType(TrackedEmail email, EventType type);

    /**
     * Count genuine (non-bot) events of a specific type.
     * Used by TrackingService hot-path to exclude suspected-bot opens
     * from lead-score and notification counts.
     */
    long countByEmailAndTypeAndSuspectedBotFalse(TrackedEmail email, EventType type);

    /**
     * Most recent genuine (non-bot) open for an email.
     * Used in the recordClick hot-path so that a bot pre-fetch does not
     * artificially inflate the recency component of the lead score.
     */
    java.util.Optional<TrackingEvent> findFirstByEmailAndTypeAndSuspectedBotFalseOrderByTimestampDesc(
            TrackedEmail email, EventType type);

    /**
     * Returns the single [dayOfWeek, hour, count] row with the most OPEN events for a user.
     *   col[0] dayOfWeek  — ISO day-of-week int, 1=Monday … 7=Sunday
     *   col[1] hour       — hour of day 0-23
     *   col[2] openCount  — number of opens in that slot
     *
     * Note: uses CAST(... AS INT) instead of ::INT — Spring JPA interprets ::x as a named parameter.
     */
    @Query(value = """
        SELECT CAST(EXTRACT(ISODOW FROM e.timestamp) AS INT),
               CAST(EXTRACT(HOUR  FROM e.timestamp) AS INT),
               COUNT(*)
        FROM tracking_events e
        JOIN tracked_emails  te ON te.id = e.email_id
        JOIN users           u  ON u.id  = te.user_id
        WHERE u.email = :userEmail
          AND e.type  = 'OPEN'
        GROUP BY 1, 2
        ORDER BY 3 DESC
        LIMIT 1
        """, nativeQuery = true)
    List<Object[]> findBestSendSlot(@Param("userEmail") String userEmail);

    /**
     * Count total OPEN events for a user (used by AIService to decide
     * whether there is enough data for send-time analysis).
     */
    @Query("""
        SELECT COUNT(e) FROM TrackingEvent e
        WHERE e.email.user.email = :userEmail
          AND e.type = com.nudge.model.EventType.OPEN
        """)
    long countOpensByUserEmail(@Param("userEmail") String userEmail);
}
