-- Source facts for one verified target. Does not mark an entire legacy statement as original/PIT.
CREATE TABLE IF NOT EXISTS financial_disclosure_facts (
 id uuid PRIMARY KEY,
 security_id uuid NOT NULL REFERENCES securities(id),
 target_code varchar(80) NOT NULL CHECK (target_code='NET_PROFIT_AFTER_TAX'),
 period_end date NOT NULL CHECK (extract(month FROM period_end) IN(3,6,9,12) AND period_end=(date_trunc('quarter',period_end)+interval '3 months -1 day')::date),
 report_scope varchar(24) NOT NULL CHECK (report_scope IN('CONSOLIDATED','SEPARATE')),
 period_basis varchar(32) NOT NULL CHECK (period_basis='STANDALONE_QUARTER'),
 currency char(3) NOT NULL CHECK (currency='VND'),
 unit_scale bigint NOT NULL CHECK (unit_scale=1),
 value numeric(30,8) NOT NULL,
 published_on date NOT NULL CHECK (published_on>=period_end),
 available_at timestamptz NOT NULL,
 publication_precision varchar(16) NOT NULL CHECK (publication_precision='DAY'),
 source_document_raw_id uuid NOT NULL REFERENCES raw_payloads(id),
 source_listing_raw_id uuid NOT NULL REFERENCES raw_payloads(id),
 source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
 source_document_url text NOT NULL CHECK (source_document_url ~ '^https://fpt[.]com/api/media/[A-Za-z0-9_.-]+[.]pdf$'),
 source_page integer NOT NULL CHECK (source_page>0),
 vintage_status varchar(48) NOT NULL CHECK (vintage_status='ARCHIVED_DISCLOSURE_RECONSTRUCTION'),
 eligibility varchar(24) NOT NULL CHECK (eligibility='RESEARCH_ONLY'),
 validation_evidence jsonb NOT NULL CHECK (jsonb_typeof(validation_evidence)='object'),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 UNIQUE (source_sha256,period_end,report_scope,target_code),
 CHECK (available_at=((published_on+1)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')),
 CHECK ((((validation_evidence->'numeric_checks'->>'cross_resolution_npat_agreement')::boolean IS TRUE AND ((validation_evidence->'numeric_checks'->>'npat_equals_parent_plus_minority')::boolean IS TRUE OR (validation_evidence->'numeric_checks'->>'exact_provider_value_match')::boolean IS TRUE)) OR (validation_evidence->>'validation_method'='ASSISTANT_VISUAL_REVIEW' AND (validation_evidence->'numeric_checks'->>'assistant_visual_source_review')::boolean IS TRUE AND (validation_evidence->'numeric_checks'->>'npat_equals_parent_plus_minority')::boolean IS TRUE)) IS TRUE)
);
CREATE INDEX IF NOT EXISTS idx_financial_disclosure_fact_pit ON financial_disclosure_facts(security_id,target_code,report_scope,period_end,available_at);
