-- V6 — Record why an event was not counted as a read, and who registered the email.
--
-- filter_reason is NULL for counted events and for events filtered before V6.
-- sender_ip / sender_user_agent let the server ignore the pixel load made by the
-- sender's own browser right after the email is registered.

ALTER TABLE tracking_events ADD COLUMN IF NOT EXISTS filter_reason VARCHAR(32);
ALTER TABLE tracked_emails  ADD COLUMN IF NOT EXISTS sender_ip VARCHAR(255);
ALTER TABLE tracked_emails  ADD COLUMN IF NOT EXISTS sender_user_agent VARCHAR(512);
