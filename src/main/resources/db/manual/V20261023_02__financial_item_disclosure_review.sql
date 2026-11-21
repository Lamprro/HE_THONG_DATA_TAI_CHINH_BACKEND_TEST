-- Cross-source evidence is not a certificate of an original historical release.
CREATE TABLE financial_item_disclosure_review (
 item_id uuid NOT NULL REFERENCES financial_statement_items(id),
 fact_id uuid NOT NULL REFERENCES financial_disclosure_facts(id),
 canonical_value_at_review numeric(30,8) NOT NULL,
 document_value_at_review numeric(30,8) NOT NULL,
 source_sha256 char(64) NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
 comparison varchar(32) NOT NULL CHECK(comparison IN('EXACT_VALUE_MATCH','VALUE_CONFLICT')),
 review_status varchar(40) NOT NULL DEFAULT 'NEEDS_VINTAGE_AND_SCOPE_REVIEW' CHECK(review_status='NEEDS_VINTAGE_AND_SCOPE_REVIEW'),
 training_eligible boolean NOT NULL DEFAULT false CHECK(training_eligible=false),
 reviewed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(item_id,fact_id),
 CHECK((comparison='EXACT_VALUE_MATCH')=(canonical_value_at_review=document_value_at_review))
);
CREATE INDEX idx_financial_disclosure_review_fact ON financial_item_disclosure_review(fact_id);
CREATE VIEW vw_financial_item_disclosure_review AS
SELECT q.item_id,q.fact_id,s.security_id,se.symbol,p.end_date,i.item_code,
 q.canonical_value_at_review,q.document_value_at_review,i.value AS current_canonical_value,
 q.comparison,q.review_status,q.training_eligible,q.reviewed_at,
 (i.value IS DISTINCT FROM q.canonical_value_at_review OR f.value IS DISTINCT FROM q.document_value_at_review OR f.source_sha256<>q.source_sha256) AS stale_review,
 f.report_scope AS document_report_scope,f.period_basis AS document_period_basis,
 f.currency AS document_currency,f.unit_scale AS document_unit_scale,
 f.published_on AS document_published_on,f.available_at AS document_available_at,
 f.source_document_raw_id,f.source_listing_raw_id,f.source_document_url,f.source_page,f.source_sha256,f.vintage_status
FROM financial_item_disclosure_review q JOIN financial_statement_items i ON i.id=q.item_id
JOIN financial_statements s ON s.id=i.financial_statement_id JOIN financial_periods p ON p.id=s.financial_period_id
JOIN securities se ON se.id=s.security_id JOIN financial_disclosure_facts f ON f.id=q.fact_id;
