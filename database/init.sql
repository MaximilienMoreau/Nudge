-- ─────────────────────────────────────────────────────────────
-- Nudge — Database initialization script
-- Run once to create the database and user.
-- Hibernate will create/migrate tables automatically via ddl-auto=update
-- ─────────────────────────────────────────────────────────────

-- Create the database (run as postgres superuser)
CREATE DATABASE nudge;

-- Optional: create a dedicated user
-- CREATE USER nudge_user WITH PASSWORD 'your_password';
-- GRANT ALL PRIVILEGES ON DATABASE nudge TO nudge_user;
