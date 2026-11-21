SELECT symbol AS ticker, period_type, end_date, report_scope, period_basis, currency,
       unit_scale, statement_type, statement_id, item_id, item_code, value, source_published_at,
       known_at, strict_point_in_time_eligible, raw_payload_id, data_version_id,
       coalesce(metadata_provenance->'source'->>'audit_status','UNKNOWN') AS audit_status
FROM vw_training_statement_inputs_v1
WHERE (statement_type='INCOME_STATEMENT' AND item_code IN ('NET_PROFIT_AFTER_TAX','NET_SALES'))
   OR (statement_type='BALANCE_SHEET' AND item_code IN ('TOTAL_ASSETS','OWNERS_EQUITY','LIABILITIES'))
ORDER BY symbol,end_date,item_code,statement_id,item_id
