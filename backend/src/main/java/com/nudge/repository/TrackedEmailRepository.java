package com.nudge.repository;

import com.nudge.model.TrackedEmail;
import com.nudge.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackedEmailRepository extends JpaRepository<TrackedEmail, Long> {

    /** All emails for the authenticated user, newest first */
    List<TrackedEmail> findByUserOrderByCreatedAtDesc(User user);

    /** Look up an email by its unique tracking UUID (used in pixel endpoint) */
    Optional<TrackedEmail> findByTrackingId(String trackingId);
}
