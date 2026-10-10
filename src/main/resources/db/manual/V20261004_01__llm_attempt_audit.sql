CREATE TABLE IF NOT EXISTS llm_run_attempts (
    id uuid PRIMARY KEY,
    llm_run_id uuid NOT NULL REFERENCES llm_runs(id) ON DELETE CASCADE,
    attempt_no integer NOT NULL CHECK (attempt_no>0),
    model_name varchar(150) NOT NULL,
    http_status integer,
    error_category varchar(100),
    request_payload jsonb NOT NULL,
    response_body text,
    response_text text,
    input_tokens integer,
    output_tokens integer,
    latency_ms integer NOT NULL CHECK (latency_ms>=0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(llm_run_id,attempt_no)
);
