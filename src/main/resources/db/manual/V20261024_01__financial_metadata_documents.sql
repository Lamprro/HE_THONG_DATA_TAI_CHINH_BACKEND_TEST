-- Additive, idempotent. Historical vendor values and dates are not backfilled by inference.
ALTER TABLE financial_statements ADD COLUMN IF NOT EXISTS period_basis varchar(32) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE financial_statements ADD COLUMN IF NOT EXISTS metadata_provenance jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE financial_statements ALTER COLUMN currency DROP NOT NULL;
ALTER TABLE financial_statements ALTER COLUMN unit_scale DROP NOT NULL;
ALTER TABLE financial_statements ALTER COLUMN is_restated DROP NOT NULL;

CREATE TABLE IF NOT EXISTS financial_disclosure_documents (
 id bigserial PRIMARY KEY,
 source_code varchar(40) NOT NULL CHECK(source_code='FPT_IR'),
 symbol varchar(20) NOT NULL CHECK(symbol='FPT'),
 document_id varchar(160) NOT NULL,
 record_sha256 varchar(64) NOT NULL CHECK(record_sha256 ~ '^[a-f0-9]{64}$'),
 listing_sha256 varchar(64) NOT NULL CHECK(listing_sha256 ~ '^[a-f0-9]{64}$'),
 source_raw_payload_id uuid NOT NULL REFERENCES raw_payloads(id),
 period_end date NOT NULL,
 published_on date NOT NULL CHECK(published_on>=period_end),
 available_at timestamptz NOT NULL,
 report_scope varchar(20) NOT NULL CHECK(report_scope IN ('CONSOLIDATED','SEPARATE','UNKNOWN')),
 revision_status varchar(16) NOT NULL CHECK(revision_status IN ('RESTATED','UNKNOWN')),
 report_version_id varchar(200) NOT NULL,
 metadata jsonb NOT NULL,
 observed_at timestamptz NOT NULL DEFAULT now(),
 training_eligible boolean NOT NULL DEFAULT false CHECK(NOT training_eligible),
 UNIQUE(source_code,document_id,record_sha256)
);
CREATE INDEX IF NOT EXISTS idx_disclosure_documents_symbol_period
 ON financial_disclosure_documents(symbol,period_end DESC,published_on DESC);

INSERT INTO data_sources(code,name,source_type,base_url,provider,priority,is_official,license_status,is_active,created_at,updated_at)
VALUES ('FPT_IR','FPT official investor disclosures','API','https://fpt.com','fpt-ir',100,true,'UNKNOWN',true,now(),now())
ON CONFLICT(code) DO NOTHING;
