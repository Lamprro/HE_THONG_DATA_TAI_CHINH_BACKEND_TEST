-- Preserve period identity when publishing several horizons or target sets.
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS forecast_period_start date;
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS forecast_period_end date;
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS target_scope text;
UPDATE llm_results r SET
 forecast_period_end=(result_json->>'forecast_period_end')::date,
 forecast_period_start=date_trunc('quarter',(result_json->>'forecast_period_end')::date)::date,
 target_scope=(SELECT string_agg(DISTINCT f->>'metric_code',',' ORDER BY f->>'metric_code')
               FROM jsonb_array_elements(r.result_json->'forecasts') f)
WHERE source_domain='FINANCIAL'
 AND (forecast_period_start IS NULL OR forecast_period_end IS NULL OR target_scope IS NULL);
-- Invalid historical rows must be reviewed; never invent a period or target.
ALTER TABLE llm_results DROP CONSTRAINT IF EXISTS ck_forecast_result_period;
ALTER TABLE llm_results ADD CONSTRAINT ck_forecast_result_period CHECK (
 source_domain <> 'FINANCIAL' OR (
 forecast_period_start IS NOT NULL AND forecast_period_end IS NOT NULL
 AND target_scope IS NOT NULL AND length(target_scope)>0
 AND forecast_period_start=date_trunc('quarter',forecast_period_end)::date
 AND forecast_period_end=(forecast_period_start+interval '3 months'-interval '1 day')::date
 AND as_of_date<=forecast_period_end AND result_json ? 'forecast_period_end'
 AND forecast_period_end=(result_json->>'forecast_period_end')::date));
DROP INDEX IF EXISTS ux_forecast_current_result;
CREATE UNIQUE INDEX IF NOT EXISTS ux_forecast_current_period
 ON llm_results(security_id,task_code,forecast_period_start,forecast_period_end,target_scope)
 WHERE source_domain='FINANCIAL' AND is_current;
