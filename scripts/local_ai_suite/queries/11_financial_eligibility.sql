SELECT count(*) AS canonical_items,count(DISTINCT statement_id) AS canonical_statements,
 count(DISTINCT security_id) AS securities,
 count(*) FILTER(WHERE strict_point_in_time_eligible) AS strict_pit_items,
 count(DISTINCT statement_id) FILTER(WHERE strict_point_in_time_eligible) AS strict_pit_statements,
 count(*) FILTER(WHERE source_published_at IS NULL) AS missing_publication,
 count(*) FILTER(WHERE report_scope NOT IN ('CONSOLIDATED','SEPARATE')) AS unknown_scope,
 count(*) FILTER(WHERE period_basis='UNKNOWN') AS unknown_period_basis,
 count(*) FILTER(WHERE currency IS NULL OR unit_scale IS NULL OR unit_scale<=0) AS bad_unit,
 count(*) FILTER(WHERE metadata_contract<>'financial.metadata.v1') AS unverified_contract,
 min(end_date) AS first_period,max(end_date) AS last_period,min(known_at) AS first_known_at,max(known_at) AS last_known_at
FROM vw_training_statement_inputs_v1
