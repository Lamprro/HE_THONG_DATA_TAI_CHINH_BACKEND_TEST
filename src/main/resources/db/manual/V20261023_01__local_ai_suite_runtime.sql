-- Immutable research packages and feature snapshots. Existing H1 tables are unaffected.
CREATE TABLE local_ai_packages (
 id uuid PRIMARY KEY,
 manifest_sha256 char(64) NOT NULL UNIQUE CHECK(manifest_sha256 ~ '^[0-9a-f]{64}$'),
 manifest jsonb NOT NULL CHECK(jsonb_typeof(manifest)='object'),
 status varchar(24) NOT NULL DEFAULT 'RESEARCH_ONLY' CHECK(status='RESEARCH_ONLY'),
 registered_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE local_ai_package_inputs (
 package_id uuid NOT NULL REFERENCES local_ai_packages(id),
 model_id varchar(60) NOT NULL,
 ticker varchar(12) NOT NULL,
 data_as_of varchar(64) NOT NULL,
 input_sha256 char(64) NOT NULL CHECK(input_sha256 ~ '^[0-9a-f]{64}$'),
 input_payload text NOT NULL CHECK(jsonb_typeof(input_payload::jsonb)='object'),
 PRIMARY KEY(package_id,model_id,ticker)
);
CREATE TABLE local_ai_package_runs (
 id uuid PRIMARY KEY,
 request_id uuid NOT NULL,
 package_id uuid NOT NULL REFERENCES local_ai_packages(id),
 model_id varchar(60) NOT NULL,
 ticker varchar(12) NOT NULL,
 role varchar(16) NOT NULL CHECK(role IN('selected','challenger','baseline')),
 request_hash char(64) NOT NULL,
 status varchar(24) NOT NULL CHECK(status IN('RUNNING','SUCCEEDED','CACHED','INPUT_REJECTED','PROVIDER_FAILED')),
 cached_from uuid REFERENCES local_ai_package_runs(id),
 request_json jsonb,
 response_json jsonb,
 checks jsonb,
 http_status integer,
 latency_ms bigint,
 error_code varchar(100),
 started_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 CHECK((status='RUNNING' AND completed_at IS NULL) OR (status<>'RUNNING' AND completed_at IS NOT NULL)),
 CHECK(status<>'CACHED' OR (cached_from IS NOT NULL AND http_status IS NULL)),
 CHECK(status<>'SUCCEEDED' OR (http_status=200 AND response_json IS NOT NULL AND (checks->>'passed')::boolean IS TRUE) IS TRUE)
);
-- Only the original claim holds the idempotency key. Every cache/conflict call has its own audit row.
CREATE UNIQUE INDEX uq_local_ai_package_request ON local_ai_package_runs(package_id,request_id) WHERE cached_from IS NULL;
CREATE INDEX idx_local_ai_package_runs_history ON local_ai_package_runs(package_id,started_at DESC);
CREATE TABLE local_ai_package_predictions (
 id uuid PRIMARY KEY,
 run_id uuid NOT NULL UNIQUE REFERENCES local_ai_package_runs(id),
 package_id uuid NOT NULL REFERENCES local_ai_packages(id),
 model_id varchar(60) NOT NULL,
 ticker varchar(12) NOT NULL,
 role varchar(16) NOT NULL,
 target_period char(6) NOT NULL CHECK(target_period ~ '^[0-9]{4}Q[1-4]$'),
 issued_at timestamptz NOT NULL,
 prediction jsonb NOT NULL CHECK(jsonb_typeof(prediction)='object'),
 readiness varchar(24) NOT NULL CHECK(readiness='RESEARCH_ONLY'),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_local_ai_package_prediction_target ON local_ai_package_predictions(model_id,ticker,target_period);
