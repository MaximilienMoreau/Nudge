ALTER TABLE tracked_emails ALTER COLUMN subject TYPE VARCHAR(500);
CREATE INDEX IF NOT EXISTS idx_tracking_events_genuine_email_type_time
    ON tracking_events(email_id, type, timestamp DESC) WHERE suspected_bot = FALSE;
