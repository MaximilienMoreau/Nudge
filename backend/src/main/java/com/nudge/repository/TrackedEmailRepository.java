package com.nudge.repository;

import com.nudge.model.TrackedEmail;
import com.nudge.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackedEmailRepository extends JpaRepository<TrackedEmail, Long> {

    /**
     * All active (non-archived) emails for a user, newest first.
     * Filtering archived emails via archivedAt IS NULL.
     * Pageable support for large lists.
     */
    Page<TrackedEmail> findByUserAndArchivedAtIsNullOrderByCreatedAtDesc(User user, Pageable pageable);

    /** Unpaged version — used by scheduler and small user accounts. */
    List<TrackedEmail> findByUserAndArchivedAtIsNullOrderByCreatedAtDesc(User user);

    /**
     * Find emails where a follow-up was scheduled before now
     * and has not yet been archived. Pageable variant prevents loading
     * unbounded results into memory in the scheduler.
     */
    Slice<TrackedEmail> findByScheduledFollowUpAtIsNotNullAndArchivedAtIsNull(Pageable pageable);

    /** Archived emails for a user, most recently archived first. */
    List<TrackedEmail> findByUserAndArchivedAtIsNotNullOrderByArchivedAtDesc(User user);

    /** Look up an email by its unique tracking UUID (used in pixel endpoint). */
    Optional<TrackedEmail> findByTrackingId(String trackingId);
}
