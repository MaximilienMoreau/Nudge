-- ─────────────────────────────────────────────────────────────
-- V3 — Performance indexes
-- ─────────────────────────────────────────────────────────────

-- Composite index covering the two most frequent query patterns:
--   countByEmailAndType and findFirstByEmailAndTypeOrderByTimestampDesc.
-- Replaces the standalone idx_tracking_events_type which was not selective
-- (only 2 distinct values: OPEN / CLICK).
CREATE INDEX IF NOT EXISTS idx_tracking_events_email_type
    ON tracking_events(email_id, type);

DROP INDEX IF EXISTS idx_tracking_events_type;

-- Partial index for the follow-up scheduler.
-- The scheduler queries: WHERE scheduled_follow_up_at IS NOT NULL AND archived_at IS NULL.
-- A partial index on the minority of rows where follow-ups are pending is much
-- smaller and faster than a full-table index.
CREATE INDEX IF NOT EXISTS idx_tracked_emails_follow_up
    ON tracked_emails(scheduled_follow_up_at)
    WHERE scheduled_follow_up_at IS NOT NULL AND archived_at IS NULL;
