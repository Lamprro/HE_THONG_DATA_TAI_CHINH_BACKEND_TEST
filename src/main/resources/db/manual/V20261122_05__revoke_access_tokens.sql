CREATE TABLE IF NOT EXISTS revoked_access_tokens (
    token_id UUID PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS revoked_access_tokens_expires_at_idx
    ON revoked_access_tokens (expires_at);
