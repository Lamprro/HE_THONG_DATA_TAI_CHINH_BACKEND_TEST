BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE data_versions ADD COLUMN IF NOT EXISTS reviewed_at timestamptz;
ALTER TABLE data_versions ADD COLUMN IF NOT EXISTS reviewed_by_user_id uuid REFERENCES users(id);
ALTER TABLE data_versions DROP CONSTRAINT IF EXISTS data_versions_status_check;
ALTER TABLE data_versions ADD CONSTRAINT data_versions_status_check CHECK (
  status IN ('DRAFT','VALIDATING','PENDING_REVIEW','ACTIVE','ACTIVATED','SUPERSEDED','REJECTED','ARCHIVED')
);

-- Materialization checks record these results after raw validation.
INSERT INTO validation_rules
 (code,name,data_domain,severity,rule_type,executor_key,rule_config,description,is_active,created_at,updated_at)
VALUES ('CROSS_SOURCE_DIFFERENCE','Cross-source discrepancy','CROSS_SOURCE','WARNING','BUSINESS',
        'CROSS_SOURCE_DIFFERENCE','{}','Hold the version for operator review before changing canonical data.',true,now(),now())
ON CONFLICT (code) DO NOTHING;
COMMIT;
