ALTER TABLE admin_commands ADD COLUMN IF NOT EXISTS progress_json jsonb;
ALTER TABLE admin_commands ADD COLUMN IF NOT EXISTS parent_command_id uuid REFERENCES admin_commands(id);
ALTER TABLE admin_commands DROP CONSTRAINT IF EXISTS admin_commands_status_check;
ALTER TABLE admin_commands ADD CONSTRAINT admin_commands_status_check
 CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','UNKNOWN','ACKNOWLEDGED','CANCELLED'));

CREATE TABLE IF NOT EXISTS admin_operation_events (
 id uuid PRIMARY KEY,
 event_type varchar(40) NOT NULL,
 command_id uuid REFERENCES admin_commands(id),
 target_id uuid,
 reason varchar(2000),
 http_method varchar(10),
 request_path varchar(1000),
 http_status integer,
 trace_id varchar(100),
 principal varchar(100) NOT NULL DEFAULT 'DEDICATED_ADMIN_TOKEN',
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS admin_operation_events_created ON admin_operation_events(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS admin_operation_events_target ON admin_operation_events(target_id,created_at DESC);

CREATE TABLE IF NOT EXISTS admin_schedule_policy (
 id varchar(40) PRIMARY KEY,
 enabled boolean NOT NULL DEFAULT false,
 job_codes jsonb NOT NULL DEFAULT '[]'::jsonb,
 version bigint NOT NULL DEFAULT 0,
 reason varchar(2000),
 updated_at timestamptz NOT NULL DEFAULT now(),
 heartbeat_at timestamptz,
 last_sweep_started_at timestamptz,
 last_sweep_finished_at timestamptz,
 last_error text
);
INSERT INTO admin_schedule_policy(id) VALUES ('INGESTION') ON CONFLICT DO NOTHING;
