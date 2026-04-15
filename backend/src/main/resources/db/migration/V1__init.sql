-- ─────────────────────────────────────────────────────────────
-- V1 — Initial schema
-- Matches the Hibernate-generated DDL so Flyway can baseline.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    id           BIGSERIAL    PRIMARY KEY,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS tracked_emails (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject         VARCHAR(255) NOT NULL,
    content         TEXT,
    recipient_email VARCHAR(255),
    tracking_id     VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS tracking_events (
    id         BIGSERIAL    PRIMARY KEY,
    email_id   BIGINT       NOT NULL REFERENCES tracked_emails(id) ON DELETE CASCADE,
    type       VARCHAR(50)  NOT NULL,
    timestamp  TIMESTAMP    NOT NULL DEFAULT NOW(),
    ip_address VARCHAR(255),
    user_agent TEXT
);
