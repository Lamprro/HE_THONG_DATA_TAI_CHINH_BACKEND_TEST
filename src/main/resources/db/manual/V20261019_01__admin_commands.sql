-- Additive operator ledger. No changes to canonical financial data or LLM state.
CREATE TABLE IF NOT EXISTS admin_commands (
  id uuid PRIMARY KEY,
  idempotency_key uuid NOT NULL UNIQUE,
  request_hash varchar(64) NOT NULL,
  action varchar(40) NOT NULL,
  resource_key varchar(250) NOT NULL,
  reason varchar(2000) NOT NULL,
  principal varchar(100) NOT NULL DEFAULT 'DEDICATED_ADMIN_TOKEN',
  status varchar(30) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN','ACKNOWLEDGED')),
  request_json jsonb NOT NULL,
  result_json jsonb,
  error_message text,
  owner_id uuid,
  lease_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  acknowledgement_note varchar(2000)
);
CREATE UNIQUE INDEX IF NOT EXISTS admin_commands_one_active_resource
  ON admin_commands(resource_key) WHERE status IN ('QUEUED','RUNNING','UNKNOWN');
CREATE INDEX IF NOT EXISTS admin_commands_created ON admin_commands(created_at DESC, id DESC);
COMMENT ON TABLE admin_commands IS 'Native flow dispatch ledger; UNKNOWN requires checking original runs before acknowledging. Token principal is not a verified human identity.';
