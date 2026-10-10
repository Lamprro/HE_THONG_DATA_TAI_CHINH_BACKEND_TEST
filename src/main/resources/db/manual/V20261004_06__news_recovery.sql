-- Additive only: reuse LLM tables; no new table, column or status enum.
CREATE INDEX IF NOT EXISTS ix_llm_news_recovery_review
 ON llm_runs(created_at DESC,id) WHERE task_code='NEWS_CONTENT_RECOVERY' AND status='PENDING_VALIDATION';
-- Fail rather than deleting/merging data if pre-existing duplicate current bodies exist.
-- Protect recovery and normal ingestion against concurrent same-content publications.
CREATE UNIQUE INDEX IF NOT EXISTS ux_news_unique_content_hash
 ON news_articles(content_hash) WHERE content_hash IS NOT NULL AND dedup_status='UNIQUE';
INSERT INTO validation_rules(code,name,data_domain,severity,rule_type,executor_key,rule_config,description,is_active,created_at,updated_at)
SELECT code,name,'NEWS_RECOVERY','ERROR','CUSTOM',code,'{"version":"1"}'::jsonb,name,true,now(),now()
FROM (VALUES
 ('RECOVERY_SCHEMA','Recovery response matches prompt schema'),
 ('RECOVERY_SOURCE','Source article and retrieval evidence match'),
 ('RECOVERY_CONTENT','Proposed article has usable body and quoted evidence'),
 ('RECOVERY_SNAPSHOT','Original article has not changed'),
 ('RECOVERY_POLICY','Prompt and validation policy are unchanged')
) AS definitions(code,name) ON CONFLICT(code) DO NOTHING;
