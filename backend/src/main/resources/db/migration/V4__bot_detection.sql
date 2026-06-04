-- V4 — Bot detection: persist suspected_bot flag on tracking events
--
-- Events where suspected_bot = TRUE are excluded from lead scoring and
-- real-time notifications. They remain in the DB for audit purposes.

ALTER TABLE tracking_events
    ADD COLUMN IF NOT EXISTS suspected_bot BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index: only covers the rare TRUE rows, negligible overhead for normal ops.
CREATE INDEX IF NOT EXISTS idx_tracking_events_bot
    ON tracking_events(suspected_bot)
    WHERE suspected_bot = TRUE;
