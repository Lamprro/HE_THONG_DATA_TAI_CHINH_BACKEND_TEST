-- Run transactionally after backing up the target schema. No provider call or source-data writes.
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS forecast_period_start date;
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS forecast_period_end date;
ALTER TABLE llm_results ADD COLUMN IF NOT EXISTS target_scope text;

UPDATE llm_results r SET
  forecast_period_end=(r.result_json->>'forecast_period_end')::date,
  forecast_period_start=date_trunc('quarter',(r.result_json->>'forecast_period_end')::date)::date,
  target_scope=(SELECT string_agg(code,',' ORDER BY code) FROM
    (SELECT DISTINCT x->>'metric_code' code FROM jsonb_array_elements(r.result_json->'forecasts') x) targets)
WHERE r.source_domain='FINANCIAL' AND r.forecast_period_end IS NULL;

-- Preserve all historical results. Only the latest data cutoff/run remains current per period/scope.
WITH ranked AS (
  SELECT id,row_number() OVER (PARTITION BY security_id,task_code,forecast_period_start,
    forecast_period_end,target_scope ORDER BY as_of_date DESC,created_at DESC,id DESC) rn
  FROM llm_results WHERE source_domain='FINANCIAL' AND is_current
)
UPDATE llm_results r SET is_current=false FROM ranked x WHERE r.id=x.id AND x.rn>1;

ALTER TABLE llm_results DROP CONSTRAINT IF EXISTS ck_forecast_result_period;
ALTER TABLE llm_results ADD CONSTRAINT ck_forecast_result_period CHECK (
  source_domain <> 'FINANCIAL' OR (
    forecast_period_start IS NOT NULL AND forecast_period_end IS NOT NULL
    AND target_scope IS NOT NULL AND length(target_scope)>0
    AND forecast_period_start=date_trunc('quarter',forecast_period_end)::date
    AND forecast_period_end=(forecast_period_start+interval '3 months'-interval '1 day')::date
    AND as_of_date<=forecast_period_end
    AND result_json ? 'forecast_period_end'
    AND forecast_period_end=(result_json->>'forecast_period_end')::date
  )
);
DROP INDEX IF EXISTS ux_forecast_current_result;
CREATE UNIQUE INDEX IF NOT EXISTS ux_forecast_current_period ON llm_results
  (security_id,task_code,forecast_period_start,forecast_period_end,target_scope)
  WHERE source_domain='FINANCIAL' AND is_current;

-- Existing deployed writers omit these new columns. Derive them from their audited JSON,
-- and enforce period replacement in the same transaction until the new application is deployed.
CREATE OR REPLACE FUNCTION set_forecast_result_period() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_scope text;
BEGIN
  IF NEW.source_domain='FINANCIAL' THEN
    NEW.forecast_period_end=coalesce(NEW.forecast_period_end,(NEW.result_json->>'forecast_period_end')::date);
    NEW.forecast_period_start=coalesce(NEW.forecast_period_start,date_trunc('quarter',NEW.forecast_period_end)::date);
    SELECT string_agg(code,',' ORDER BY code) INTO expected_scope FROM
      (SELECT DISTINCT x->>'metric_code' code FROM jsonb_array_elements(NEW.result_json->'forecasts') x) targets;
    NEW.target_scope=coalesce(NEW.target_scope,expected_scope);
    IF NEW.target_scope IS DISTINCT FROM expected_scope THEN
      RAISE EXCEPTION 'Forecast target scope differs from result targets';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('forecast:'||NEW.security_id::text,0));
    IF NEW.is_current THEN
      IF EXISTS (SELECT 1 FROM llm_results r WHERE r.source_domain='FINANCIAL'
          AND r.security_id=NEW.security_id AND r.task_code=NEW.task_code
          AND r.forecast_period_end=NEW.forecast_period_end AND r.target_scope=NEW.target_scope
          AND r.is_current AND r.as_of_date>NEW.as_of_date) THEN
        NEW.is_current=false;
      ELSE
        UPDATE llm_results SET is_current=false WHERE source_domain='FINANCIAL'
          AND security_id=NEW.security_id AND task_code=NEW.task_code
          AND forecast_period_end=NEW.forecast_period_end AND target_scope=NEW.target_scope AND is_current;
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_forecast_result_period ON llm_results;
CREATE TRIGGER trg_forecast_result_period BEFORE INSERT ON llm_results
  FOR EACH ROW EXECUTE FUNCTION set_forecast_result_period();
