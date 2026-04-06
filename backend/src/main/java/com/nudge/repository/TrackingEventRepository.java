package com.nudge.repository;

import com.nudge.model.EventType;
import com.nudge.model.TrackedEmail;
import com.nudge.model.TrackingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackingEventRepository extends JpaRepository<TrackingEvent, Long> {

    /** All events for a given email, ordered by time */
    List<TrackingEvent> findByEmailOrderByTimestampDesc(TrackedEmail email);

    /** Count events of a specific type for an email (e.g. count opens) */
    long countByEmailAndType(TrackedEmail email, EventType type);
}
