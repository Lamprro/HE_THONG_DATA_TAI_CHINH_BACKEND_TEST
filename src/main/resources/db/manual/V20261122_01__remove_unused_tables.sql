BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE roles, accounts IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
  IF (SELECT count(*) FROM roles WHERE code IN ('ADMIN', 'USER')) <> 2 THEN
    RAISE EXCEPTION 'Expected existing roles ADMIN and USER';
  END IF;
  IF EXISTS (
    SELECT 1 FROM accounts a JOIN roles r ON r.id = a.role_id WHERE r.code NOT IN ('ADMIN', 'USER')
  ) THEN
    RAISE EXCEPTION 'Move affected accounts to an approved role before retiring older roles';
  END IF;
END $$;

DELETE FROM roles WHERE code NOT IN ('ADMIN', 'USER');
ALTER TABLE roles DROP CONSTRAINT IF EXISTS roles_admin_user_only;
ALTER TABLE roles ADD CONSTRAINT roles_admin_user_only CHECK (code IN ('ADMIN', 'USER'));

-- Unused tables have no application consumers. Keep the raw payload, run and data-version tables.
DROP TABLE IF EXISTS audit_logs, system_settings, data_lineage_events, news_crawl_controls;

-- Remove the two writers first so updates to price/macro rows remain safe.
DROP TRIGGER IF EXISTS trg_price_training_correction ON market_prices;
DROP TRIGGER IF EXISTS trg_macro_training_correction ON macro_observations;
DROP FUNCTION IF EXISTS capture_training_data_correction();
DROP TABLE IF EXISTS data_correction_audit;
COMMIT;
