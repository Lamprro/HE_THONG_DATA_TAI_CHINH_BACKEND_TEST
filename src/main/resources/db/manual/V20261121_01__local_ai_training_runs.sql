-- Migration: Training run provenance tracking
-- Date: 2026-11-21

CREATE TABLE IF NOT EXISTS local_ai_training_runs (
  id uuid PRIMARY KEY,
  trigger varchar(16) NOT NULL CHECK(trigger IN ('MANUAL', 'AUTO')),
  reason text,
  requested_by varchar(64),
  idempotency_key uuid UNIQUE,
  status varchar(24) NOT NULL CHECK(status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED')),
  readiness_sha256 char(64),
  snapshot_sha256 char(64),
  protocol_version varchar(32),
  output_path text,
  summary jsonb,
  selected_model varchar(64),
  gate jsonb,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  error text
);

CREATE INDEX IF NOT EXISTS idx_local_ai_training_runs_status ON local_ai_training_runs(status);
CREATE INDEX IF NOT EXISTS idx_local_ai_training_runs_started ON local_ai_training_runs(started_at DESC);
