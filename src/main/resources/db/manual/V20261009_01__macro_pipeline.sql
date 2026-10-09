-- Additive only. The existing unique keys are (data_source_id,code) and (macro_series_id,observation_date).
BEGIN;
ALTER TABLE macro_observations ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
-- Widen the old numeric(38,10) so source decimals are preserved rather than silently rounded.
ALTER TABLE macro_observations ALTER COLUMN value TYPE numeric;
CREATE INDEX IF NOT EXISTS ix_macro_observations_version ON macro_observations(data_version_id);
COMMIT;
