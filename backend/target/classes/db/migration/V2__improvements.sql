-- ─────────────────────────────────────────────────────────────
-- V2 — Improvements: security, features, performance
-- ─────────────────────────────────────────────────────────────

-- S6: JWT token revocation — invalidate all tokens on password change / logout
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0;

-- F1: Soft-delete for tracked emails (archivedAt = null means active)
ALTER TABLE tracked_emails
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;

-- F4: Scheduled follow-up reminder
ALTER TABLE tracked_emails
    ADD COLUMN IF NOT EXISTS scheduled_follow_up_at TIMESTAMP;

-- P6: Performance indexes
CREATE INDEX IF NOT EXISTS idx_tracked_emails_user_id    ON tracked_emails(user_id);
CREATE INDEX IF NOT EXISTS idx_tracked_emails_tracking_id ON tracked_emails(tracking_id);
CREATE INDEX IF NOT EXISTS idx_tracked_emails_archived_at ON tracked_emails(archived_at);
CREATE INDEX IF NOT EXISTS idx_tracking_events_email_id  ON tracking_events(email_id);
CREATE INDEX IF NOT EXISTS idx_tracking_events_type      ON tracking_events(type);
CREATE INDEX IF NOT EXISTS idx_tracking_events_timestamp ON tracking_events(timestamp);
