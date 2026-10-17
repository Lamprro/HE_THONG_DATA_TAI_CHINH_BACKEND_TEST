-- Additive metadata migration. Run in a transaction after backup; do not guess historical facts.
ALTER TABLE financial_statements ADD COLUMN IF NOT EXISTS period_basis varchar(32) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE financial_statements ADD COLUMN IF NOT EXISTS metadata_provenance jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE financial_statements ALTER COLUMN currency DROP NOT NULL;
ALTER TABLE financial_statements ALTER COLUMN unit_scale DROP NOT NULL;
ALTER TABLE financial_statement_items ALTER COLUMN unit DROP NOT NULL;
ALTER TABLE financial_statements DROP CONSTRAINT IF EXISTS ck_financial_period_basis;
ALTER TABLE financial_statements ADD CONSTRAINT ck_financial_period_basis CHECK
    (period_basis IN ('UNKNOWN','POINT_IN_TIME','STANDALONE_QUARTER','YTD','ANNUAL'));
ALTER TABLE financial_statements DROP CONSTRAINT IF EXISTS ck_financial_unit_scale;
ALTER TABLE financial_statements ADD CONSTRAINT ck_financial_unit_scale CHECK (unit_scale IS NULL OR unit_scale>0);
-- Existing currency/scale values retain their historical meaning; no source-backed claim is added.
INSERT INTO validation_rules(code,name,data_domain,severity,rule_type,executor_key,rule_config,description,is_active,created_at,updated_at)
VALUES ('FINANCIAL_METADATA_VALID','Financial metadata evidence','FINANCIAL_STATEMENT','ERROR','FORMAT',
    'FINANCIAL_METADATA_VALID','{"version":"1"}',
    'Validate explicit metadata contract. Unknown is allowed; fiscal/created dates are not publication dates.',true,now(),now())
ON CONFLICT (code) DO NOTHING;
