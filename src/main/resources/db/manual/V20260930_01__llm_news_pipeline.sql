-- Apply explicitly. Application startup does not migrate the shared schema.
CREATE TABLE IF NOT EXISTS llm_prompt_templates (
    id uuid PRIMARY KEY,
    task_code varchar(80) NOT NULL,
    version varchar(40) NOT NULL,
    input_domain varchar(30) NOT NULL CHECK (input_domain IN ('NEWS','FINANCIAL','MARKET')),
    description text NOT NULL,
    system_prompt text NOT NULL,
    request_schema jsonb NOT NULL,
    response_schema jsonb NOT NULL,
    checksum char(64) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(task_code, version)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_llm_prompt_active
    ON llm_prompt_templates(task_code) WHERE enabled;

ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS prompt_template_id uuid REFERENCES llm_prompt_templates(id);
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS task_code varchar(80);
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS news_article_id uuid REFERENCES news_articles(id);
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS request_payload jsonb;
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS response_payload jsonb;
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS response_text text;
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS validation_errors jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS http_status integer;
CREATE INDEX IF NOT EXISTS ix_llm_runs_template ON llm_runs(prompt_template_id);
CREATE INDEX IF NOT EXISTS ix_llm_runs_news_task ON llm_runs(news_article_id,task_code,created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS ux_llm_running_news_task ON llm_runs(news_article_id,task_code)
    WHERE status = 'RUNNING' AND news_article_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS llm_results (
    id uuid PRIMARY KEY,
    llm_run_id uuid NOT NULL UNIQUE REFERENCES llm_runs(id),
    prompt_template_id uuid NOT NULL REFERENCES llm_prompt_templates(id),
    task_code varchar(80) NOT NULL,
    source_domain varchar(30) NOT NULL CHECK (source_domain IN ('NEWS','FINANCIAL','MARKET')),
    news_article_id uuid REFERENCES news_articles(id),
    source_hash char(64) NOT NULL,
    input_hash char(64) NOT NULL,
    schema_version varchar(40) NOT NULL,
    overview text NOT NULL,
    result_json jsonb NOT NULL,
    quality_status varchar(20) NOT NULL CHECK (quality_status IN ('VALID','WARNING')),
    is_current boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Only NEWS is executable now; new domains require explicit FK-backed source migrations.
    CONSTRAINT ck_llm_result_source CHECK (source_domain = 'NEWS' AND news_article_id IS NOT NULL)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_llm_result_current ON llm_results(news_article_id,task_code) WHERE is_current;
CREATE INDEX IF NOT EXISTS ix_llm_result_template ON llm_results(prompt_template_id);
CREATE INDEX IF NOT EXISTS ix_llm_result_input ON llm_results(news_article_id,task_code,input_hash);
