-- Keep provider statements intact. Document and value versions are separate evidence.
ALTER TABLE financial_disclosure_documents ADD COLUMN IF NOT EXISTS parser_version integer NOT NULL DEFAULT 1;
DO $$ DECLARE old_constraint record; BEGIN
 FOR old_constraint IN SELECT conname FROM pg_constraint
   WHERE conrelid='financial_disclosure_documents'::regclass AND contype='u'
     AND pg_get_constraintdef(oid)='UNIQUE (source_code, document_id, record_sha256)'
 LOOP EXECUTE format('ALTER TABLE financial_disclosure_documents DROP CONSTRAINT %I',old_constraint.conname); END LOOP;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_disclosure_document_parser
 ON financial_disclosure_documents(source_code,document_id,record_sha256,parser_version);
ALTER TABLE financial_disclosure_documents DROP CONSTRAINT IF EXISTS financial_disclosure_documents_source_code_check;
ALTER TABLE financial_disclosure_documents DROP CONSTRAINT IF EXISTS financial_disclosure_documents_symbol_check;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='disclosure_registered_issuer' AND conrelid='financial_disclosure_documents'::regclass) THEN
  ALTER TABLE financial_disclosure_documents ADD CONSTRAINT disclosure_registered_issuer CHECK
   ((source_code,symbol) IN (('FPT_IR','FPT'),('HPG_IR','HPG'),('VNM_IR','VNM')));
 END IF;
END $$;

INSERT INTO data_sources(code,name,source_type,base_url,provider,priority,is_official,license_status,is_active,created_at,updated_at)
VALUES ('HPG_IR','Hoa Phat investor disclosures','API','https://file.hoaphat.com.vn','hpg-ir',100,true,'UNKNOWN',true,now(),now()),
       ('VNM_IR','Vinamilk investor disclosures','API','https://d8um25gjecm9v.cloudfront.net','vnm-ir',100,true,'UNKNOWN',true,now(),now())
ON CONFLICT(code) DO NOTHING;

CREATE TABLE IF NOT EXISTS financial_value_bindings (
 id bigserial PRIMARY KEY,
 item_id uuid NOT NULL REFERENCES financial_statement_items(id),
 fact_id uuid NOT NULL REFERENCES financial_disclosure_facts(id),
 document_id bigint NOT NULL REFERENCES financial_disclosure_documents(id),
 canonical_value numeric(30,8) NOT NULL,
 document_value numeric(30,8) NOT NULL,
 document_sha256 varchar(64) NOT NULL CHECK(document_sha256 ~ '^[a-f0-9]{64}$'),
 comparison varchar(24) NOT NULL CHECK(comparison IN ('EXACT_VALUE_MATCH','VALUE_CONFLICT')),
 -- Publication is a source claim. Observed availability is the strict usable cutoff.
 source_available_at timestamptz NOT NULL,
 usable_from timestamptz NOT NULL CHECK(usable_from >= source_available_at),
 evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(item_id,fact_id,document_id,canonical_value,document_value,document_sha256),
 CHECK((comparison='EXACT_VALUE_MATCH')=(canonical_value=document_value))
);
CREATE INDEX IF NOT EXISTS idx_value_binding_fact ON financial_value_bindings(fact_id);
CREATE INDEX IF NOT EXISTS idx_value_binding_document ON financial_value_bindings(document_id);
CREATE INDEX IF NOT EXISTS idx_value_binding_cutoff ON financial_value_bindings(usable_from,item_id);

CREATE TABLE IF NOT EXISTS financial_reconciliation_evidence (
 id bigserial PRIMARY KEY,
 item_id uuid NOT NULL REFERENCES financial_statement_items(id),
 canonical_value numeric(30,8) NOT NULL,
 evidence_value numeric(30,8) NOT NULL,
 method varchar(32) NOT NULL CHECK(method IN ('DIRECT_DOCUMENT','YTD_DIFFERENCE')),
 evidence_sha256 varchar(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
 evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 usable_from timestamptz NOT NULL,
 training_eligible boolean NOT NULL DEFAULT false CHECK(NOT training_eligible),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(item_id,canonical_value,evidence_sha256)
);

CREATE OR REPLACE VIEW vw_financial_value_bindings AS
SELECT b.*,se.symbol,p.end_date AS period_end,i.item_code,
 f.report_scope,f.period_basis,f.currency,f.unit_scale,f.source_page,
 d.report_version_id,d.revision_status,d.metadata->>'document_url' AS document_url,
 i.value AS current_value,
 (i.value IS DISTINCT FROM b.canonical_value OR f.value IS DISTINCT FROM b.document_value
  OR f.source_sha256<>b.document_sha256 OR NOT s.is_current OR NOT s.is_canonical) AS stale_binding,
 'ARCHIVED_RESEARCH_ONLY'::text AS historical_training_status
FROM financial_value_bindings b JOIN financial_statement_items i ON i.id=b.item_id
JOIN financial_statements s ON s.id=i.financial_statement_id
JOIN financial_periods p ON p.id=s.financial_period_id JOIN securities se ON se.id=s.security_id
JOIN financial_disclosure_facts f ON f.id=b.fact_id
JOIN financial_disclosure_documents d ON d.id=b.document_id;
