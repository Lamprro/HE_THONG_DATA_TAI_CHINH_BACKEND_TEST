-- Additive, source-backed training metadata. Back up changed tables before applying.
-- This migration does not invent publication dates, adjustment events or historical sectors.
CREATE INDEX IF NOT EXISTS ix_metrics_raw_identity ON financial_metrics(raw_payload_id,metric_definition_id,as_of_date);
ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS price_basis varchar(48) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS price_metadata jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS first_seen_at timestamptz;
ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS foreign_data_source_id bigint REFERENCES data_sources(id);
ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS foreign_raw_payload_id uuid REFERENCES raw_payloads(id);
ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS foreign_data_version_id uuid REFERENCES data_versions(id);
ALTER TABLE financial_statements ALTER COLUMN is_restated DROP NOT NULL;

CREATE TABLE IF NOT EXISTS security_foreign_observations (
 id uuid PRIMARY KEY,security_id uuid NOT NULL REFERENCES securities(id),trading_date date NOT NULL,
 data_source_id bigint NOT NULL REFERENCES data_sources(id),raw_payload_id uuid NOT NULL REFERENCES raw_payloads(id),
 data_version_id uuid NOT NULL REFERENCES data_versions(id),buy_volume numeric(38,6),sell_volume numeric(38,6),
 source_observed_at timestamptz NOT NULL,source_record jsonb NOT NULL,is_canonical boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(raw_payload_id,security_id,trading_date),
 CHECK(buy_volume IS NULL OR buy_volume>=0),CHECK(sell_volume IS NULL OR sell_volume>=0)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_foreign_canonical ON security_foreign_observations(security_id,trading_date) WHERE is_canonical;
CREATE INDEX IF NOT EXISTS ix_foreign_raw ON security_foreign_observations(raw_payload_id,trading_date);
CREATE TABLE IF NOT EXISTS security_reference_observations (
 id uuid PRIMARY KEY,security_id uuid NOT NULL REFERENCES securities(id),data_source_id bigint NOT NULL REFERENCES data_sources(id),
 raw_payload_id uuid NOT NULL UNIQUE REFERENCES raw_payloads(id),data_version_id uuid NOT NULL REFERENCES data_versions(id),
 observed_at timestamptz NOT NULL,source_effective_at timestamptz,industry_name text,outstanding_shares numeric(38,6),
 current_venue_listing_date date,delisted_date date,listing_status text,source_record jsonb NOT NULL,
 CHECK(outstanding_shares IS NULL OR outstanding_shares>=0)
);
CREATE INDEX IF NOT EXISTS ix_reference_security_time ON security_reference_observations(security_id,observed_at);

CREATE TABLE IF NOT EXISTS data_correction_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,table_name text NOT NULL,record_id text NOT NULL,
 previous_row jsonb NOT NULL,replacement_row jsonb NOT NULL,changed_at timestamptz NOT NULL DEFAULT now(),
 database_user text NOT NULL DEFAULT current_user
);
CREATE OR REPLACE FUNCTION capture_training_data_correction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF jsonb_build_array(to_jsonb(OLD)->'open_price',to_jsonb(OLD)->'high_price',to_jsonb(OLD)->'low_price',
    to_jsonb(OLD)->'close_price',to_jsonb(OLD)->'adjusted_close',to_jsonb(OLD)->'volume',to_jsonb(OLD)->'trading_value',
    to_jsonb(OLD)->'foreign_buy_volume',to_jsonb(OLD)->'foreign_sell_volume',to_jsonb(OLD)->'value')
    IS DISTINCT FROM jsonb_build_array(to_jsonb(NEW)->'open_price',to_jsonb(NEW)->'high_price',to_jsonb(NEW)->'low_price',
    to_jsonb(NEW)->'close_price',to_jsonb(NEW)->'adjusted_close',to_jsonb(NEW)->'volume',to_jsonb(NEW)->'trading_value',
    to_jsonb(NEW)->'foreign_buy_volume',to_jsonb(NEW)->'foreign_sell_volume',to_jsonb(NEW)->'value') THEN
  INSERT INTO data_correction_audit(table_name,record_id,previous_row,replacement_row)
  VALUES(TG_TABLE_NAME,OLD.id::text,to_jsonb(OLD),to_jsonb(NEW));
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_price_training_correction ON market_prices;
CREATE TRIGGER trg_price_training_correction AFTER UPDATE ON market_prices FOR EACH ROW EXECUTE FUNCTION capture_training_data_correction();
DROP TRIGGER IF EXISTS trg_macro_training_correction ON macro_observations;
CREATE TRIGGER trg_macro_training_correction AFTER UPDATE ON macro_observations FOR EACH ROW EXECUTE FUNCTION capture_training_data_correction();

INSERT INTO validation_rules(code,name,data_domain,severity,rule_type,executor_key,rule_config,description,is_active,created_at,updated_at)
VALUES ('FOREIGN_PAYLOAD_VALID','Source foreign-volume contract','MARKET_FOREIGN','ERROR','FORMAT','SUPPLEMENTARY_PAYLOAD_VALID','{}',
 'Exact symbol/dates, complete source page set, nonnegative optional volumes; missing is not zero.',true,now(),now()),
 ('REFERENCE_PAYLOAD_VALID','Source company reference contract','SECURITY_REFERENCE','ERROR','FORMAT','SUPPLEMENTARY_PAYLOAD_VALID','{}',
 'One matching company source row. Observed snapshot does not establish a historical effective date.',true,now(),now())
ON CONFLICT(code) DO NOTHING;

CREATE OR REPLACE VIEW vw_training_statement_inputs_v1 AS
SELECT s.id statement_id,s.company_id,s.security_id,se.symbol,s.statement_type,s.report_scope,s.period_basis,
 p.start_date,p.end_date,p.period_type,i.id item_id,i.item_code,i.value,s.currency,s.unit_scale,
 s.published_at source_published_at,coalesce(r.fetched_at,s.created_at) system_first_seen_at,
 CASE WHEN s.metadata_provenance->>'contract'='financial.metadata.v1' AND s.published_at IS NOT NULL
       AND s.is_restated=false AND s.metadata_provenance->'source'->>'revision_status'='ORIGINAL'
      THEN s.published_at+CASE WHEN s.metadata_provenance->'source'->>'publication_precision'='DAY' THEN interval '1 day' ELSE interval '0' END
      ELSE coalesce(r.fetched_at,s.created_at) END known_at,
 coalesce(s.metadata_provenance->>'contract','LEGACY_UNVERIFIED') metadata_contract,
 (s.metadata_provenance->>'contract'='financial.metadata.v1' AND s.published_at IS NOT NULL
  AND s.report_scope IN ('CONSOLIDATED','SEPARATE') AND s.period_basis<>'UNKNOWN'
  AND s.currency IS NOT NULL AND s.unit_scale>0 AND s.is_restated=false
  AND s.metadata_provenance->'source'->>'revision_status'='ORIGINAL') IS TRUE AS strict_point_in_time_eligible,
 s.raw_payload_id,s.data_version_id,s.metadata_provenance
FROM financial_statements s JOIN financial_statement_items i ON i.financial_statement_id=s.id
JOIN financial_periods p ON p.id=s.financial_period_id JOIN securities se ON se.id=s.security_id
JOIN raw_payloads r ON r.id=s.raw_payload_id JOIN data_versions v ON v.id=s.data_version_id
WHERE s.is_current AND s.is_canonical AND v.status='ACTIVATED' AND v.ingestion_run_id=r.ingestion_run_id;

CREATE OR REPLACE VIEW vw_training_market_observations_v1 AS
SELECT m.id,m.security_id,se.symbol,(m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date trading_date,
 m.data_source_id,m.raw_payload_id,m.data_version_id,m.is_canonical,m.open_price,m.high_price,m.low_price,
 m.close_price,m.adjusted_close,m.volume,m.trading_value,m.price_basis,m.price_metadata,
 coalesce(m.first_seen_at,r.fetched_at,m.created_at) system_first_seen_at,
 CASE WHEN f.id IS NOT NULL THEN f.buy_volume ELSE m.foreign_buy_volume END foreign_buy_volume,
 CASE WHEN f.id IS NOT NULL THEN f.sell_volume ELSE m.foreign_sell_volume END foreign_sell_volume,
 coalesce(f.raw_payload_id,m.foreign_raw_payload_id) foreign_raw_payload_id,
 (m.price_basis='UNADJUSTED' AND m.adjusted_close IS NOT NULL) AS price_basis_verified
FROM market_prices m JOIN securities se ON se.id=m.security_id
JOIN raw_payloads r ON r.id=m.raw_payload_id JOIN data_versions v ON v.id=m.data_version_id
LEFT JOIN security_foreign_observations f ON f.security_id=m.security_id AND f.trading_date=(m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AND f.is_canonical
WHERE m.interval_code='1d' AND v.status='ACTIVATED';
