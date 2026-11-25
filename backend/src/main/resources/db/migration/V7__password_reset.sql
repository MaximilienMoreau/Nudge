-- V7 — Password reset: only a hash of the emailed token is stored.
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_token_hash VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_token_expires_at TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_reset_token_hash ON users(reset_token_hash);
