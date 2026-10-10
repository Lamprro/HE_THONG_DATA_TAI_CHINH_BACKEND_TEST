-- Extends the existing LLM audit/result flow; never store forecast values as financial actuals.
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS company_id uuid REFERENCES companies(id);
ALTER TABLE llm_runs ADD COLUMN IF NOT EXISTS security_id uuid REFERENCES securities(id);
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS company_id uuid REFERENCES companies(id);
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS security_id uuid REFERENCES securities(id);
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS as_of_date date;
ALTER TABLE llm_results DROP CONSTRAINT IF EXISTS ck_llm_result_source;
ALTER TABLE llm_results ADD CONSTRAINT ck_llm_result_source CHECK (
 (source_domain='NEWS' AND news_article_id IS NOT NULL AND company_id IS NULL AND security_id IS NULL)
 OR (source_domain='FINANCIAL' AND news_article_id IS NULL AND company_id IS NOT NULL AND security_id IS NOT NULL AND as_of_date IS NOT NULL));
CREATE UNIQUE INDEX IF NOT EXISTS ux_forecast_active_run ON llm_runs(security_id,task_code)
 WHERE security_id IS NOT NULL AND news_article_id IS NULL AND status IN ('RUNNING','PENDING_VALIDATION');
CREATE UNIQUE INDEX IF NOT EXISTS ux_forecast_current_result ON llm_results(security_id,task_code,as_of_date)
 WHERE source_domain='FINANCIAL' AND is_current;
CREATE INDEX IF NOT EXISTS ix_forecast_runs_security ON llm_runs(security_id,created_at DESC) WHERE security_id IS NOT NULL;
ALTER TABLE financial_metrics ADD COLUMN IF NOT EXISTS input_snapshot jsonb;
ALTER TABLE financial_metrics ADD COLUMN IF NOT EXISTS calculation_key char(64);
CREATE UNIQUE INDEX IF NOT EXISTS ux_derived_metric_calculation ON financial_metrics(calculation_key) WHERE calculation_key IS NOT NULL;
INSERT INTO metric_definitions(code,name,category,description,formula,unit,higher_is_better,created_at,updated_at)
VALUES ('LIABILITIES_TO_ASSETS','Liabilities / assets','LEVERAGE','Period-end balance-sheet ratio','LIABILITIES / TOTAL_ASSETS * 100','%',false,now(),now()),
 ('EQUITY_TO_ASSETS','Equity / assets','LEVERAGE','Period-end balance-sheet ratio','OWNERS_EQUITY / TOTAL_ASSETS * 100','%',true,now(),now())
ON CONFLICT(code) DO NOTHING;
INSERT INTO validation_rules(code,name,data_domain,severity,rule_type,executor_key,rule_config,description,is_active,created_at,updated_at)
SELECT code,code,'LLM_FORECAST','ERROR','CUSTOM',code,
 '{"version":"1","tasks":["FINANCIAL_SCENARIOS"]}'::jsonb,code,true,now(),now()
FROM (VALUES ('FORECAST_SCHEMA'),('FORECAST_SOURCE'),('FORECAST_EVIDENCE'),('FORECAST_SCENARIOS'),
 ('FORECAST_COVERAGE'),('FORECAST_SNAPSHOT'),('FORECAST_PROMPT')) AS rules(code)
ON CONFLICT(code) DO NOTHING;
