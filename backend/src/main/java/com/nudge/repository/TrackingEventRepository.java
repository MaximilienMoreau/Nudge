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
     * Q4: Batch-fetch all events for a list of emails in one query.
     * Eliminates the N+1 problem in EmailService.getEmailsForUser.
     */
    @Query("SELECT e FROM TrackingEvent e WHERE e.email IN :emails ORDER BY e.timestamp DESC")
    List<TrackingEvent> findByEmailInOrderByTimestampDesc(@Param("emails") List<TrackedEmail> emails);

    /** Count events of a specific type for an email. */
    long countByEmailAndType(TrackedEmail email, EventType type);

    /**
     * Q7: Aggregate open events by day-of-week and hour directly in the DB.
     * Returns rows of [dayOfWeek (1=Mon), hour (0-23), count] sorted by count desc.
     * Replaces loading every open event into memory for send-time analysis.
     */
    @Query(value = """
        SELECT EXTRACT(ISODOW FROM e.timestamp)::INT AS day_of_week,
               EXTRACT(HOUR  FROM e.timestamp)::INT AS hour,
               COUNT(*)                              AS open_count
        FROM tracking_events e
        JOIN tracked_emails  te ON te.id = e.email_id
        JOIN users           u  ON u.id  = te.user_id
        WHERE u.email = :userEmail
          AND e.type  = 'OPEN'
        GROUP BY day_of_week, hour
        ORDER BY open_count DESC
        LIMIT 1
        """, nativeQuery = true)
    Object[] findBestSendSlot(@Param("userEmail") String userEmail);

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

    /** All OPEN events across all emails owned by a given user. */
    List<TrackingEvent> findByEmail_User_EmailAndType(String userEmail, EventType type);
}
