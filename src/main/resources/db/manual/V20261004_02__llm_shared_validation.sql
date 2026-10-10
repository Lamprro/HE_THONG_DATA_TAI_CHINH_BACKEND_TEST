-- Explicit migration; preserve existing raw validation and LLM history.
ALTER TABLE validation_results ADD COLUMN IF NOT EXISTS validation_target varchar(30) NOT NULL DEFAULT 'RAW_PAYLOAD';
ALTER TABLE validation_results ADD COLUMN IF NOT EXISTS llm_run_id uuid REFERENCES llm_runs(id) ON DELETE RESTRICT;
ALTER TABLE validation_results ADD COLUMN IF NOT EXISTS validation_round_id uuid;
ALTER TABLE validation_results ADD COLUMN IF NOT EXISTS rule_snapshot jsonb;
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS validation_round_id uuid;
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS validation_round_id uuid;
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS validation_policy_hash char(64);
ALTER TABLE llm_runs DROP CONSTRAINT IF EXISTS llm_runs_status_check;
ALTER TABLE llm_runs ADD CONSTRAINT llm_runs_status_check CHECK(status IN
 ('PENDING','RUNNING','PENDING_VALIDATION','SUCCESS','FAILED','REJECTED'));
DROP INDEX IF EXISTS ux_llm_running_news_task;
CREATE UNIQUE INDEX ux_llm_running_news_task ON llm_runs(news_article_id,task_code)
 WHERE status IN ('RUNNING','PENDING_VALIDATION') AND news_article_id IS NOT NULL;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='validation_results'::regclass AND conname='ck_validation_target') THEN
  ALTER TABLE validation_results ADD CONSTRAINT ck_validation_target CHECK (
   (validation_target='RAW_PAYLOAD' AND raw_payload_id IS NOT NULL AND llm_run_id IS NULL)
   OR (validation_target='LLM_OUTPUT' AND llm_run_id IS NOT NULL AND raw_payload_id IS NULL
       AND ingestion_run_id IS NULL AND validation_round_id IS NOT NULL AND rule_snapshot IS NOT NULL));
 END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS ux_validation_llm_round_rule
 ON validation_results(llm_run_id,validation_round_id,validation_rule_id) WHERE llm_run_id IS NOT NULL;

-- Config is declarative; executors remain in Java. Never overwrite admin changes on rerun.
INSERT INTO validation_rules(code,name,data_domain,severity,rule_type,executor_key,rule_config,description,is_active,created_at,updated_at)
SELECT code,name,'LLM_OUTPUT','ERROR','CUSTOM',code,
 jsonb_build_object('version','1','tasks',CASE WHEN code='LLM_FINANCIAL_FACTS'
 THEN '["NEWS_FINANCIAL_FACTS"]'::jsonb ELSE '["NEWS_SUMMARY","NEWS_DETAIL","NEWS_FINANCIAL_FACTS"]'::jsonb END),
 name,true,now(),now()
FROM (VALUES
 ('LLM_RESPONSE_READY','Provider response is complete valid JSON'),
 ('LLM_RESPONSE_SCHEMA','Response matches the immutable prompt schema'),
 ('LLM_SOURCE_ID','Response identifies the source article'),
 ('LLM_EVIDENCE_QUOTES','Quotes exist verbatim in their source segments'),
 ('LLM_COMPANY_REFERENCES','Company IDs exist in the request'),
 ('LLM_ANALYSIS_COMPLETENESS','Analysis status, sections and limitations are consistent'),
 ('LLM_FINANCIAL_FACTS','Financial values and metadata have direct quoted evidence'),
 ('LLM_COMPANY_IMPACT_UNIQUE','Company impacts contain no duplicate company'),
 ('LLM_SOURCE_SNAPSHOT','Source still matches the submitted snapshot'),
 ('LLM_PROMPT_SNAPSHOT','Prompt version remains active and unchanged')
) AS definitions(code,name)
ON CONFLICT(code) DO NOTHING;
