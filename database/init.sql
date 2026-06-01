-- ─────────────────────────────────────────────────────────────
-- Nudge — Database initialization script
-- Run once as postgres superuser to create the database and user.
-- Table creation and migrations are managed by Flyway
-- (see backend/src/main/resources/db/migration/).
-- ─────────────────────────────────────────────────────────────

-- Create the database (run as postgres superuser)
CREATE DATABASE nudge;

-- Optional: create a dedicated application user
-- CREATE USER nudge_user WITH PASSWORD 'your_password';
-- GRANT ALL PRIVILEGES ON DATABASE nudge TO nudge_user;
