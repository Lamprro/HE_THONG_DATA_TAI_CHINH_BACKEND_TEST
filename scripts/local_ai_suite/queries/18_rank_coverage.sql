WITH market AS (
    SELECT m.security_id,
           min((m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) AS first_day,
           max((m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) AS last_day,
           count(*) AS canonical_rows,
           count(*) FILTER (WHERE m.adjusted_close IS NULL OR m.adjusted_close <= 0) AS missing_adjusted,
           count(*) FILTER (WHERE m.price_basis IS DISTINCT FROM 'UNADJUSTED') AS unverified_basis,
           count(*) - count(DISTINCT (m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) AS duplicate_sessions,
           count(*) FILTER (WHERE r.id IS NULL OR v.id IS NULL OR v.status IS DISTINCT FROM 'ACTIVATED') AS missing_lineage
    FROM market_prices m
    LEFT JOIN raw_payloads r ON r.id=m.raw_payload_id
    LEFT JOIN data_versions v ON v.id=m.data_version_id
    WHERE m.is_canonical AND m.interval_code='1d'
    GROUP BY m.security_id
), financial AS (
    SELECT security_id, count(DISTINCT statement_id) AS canonical_statements,
           count(DISTINCT statement_id) FILTER (WHERE source_published_at IS NULL) AS no_publication,
           count(DISTINCT statement_id) FILTER (WHERE strict_point_in_time_eligible) AS point_in_time
    FROM vw_training_statement_inputs_v1
    GROUP BY security_id
)
SELECT s.symbol AS ticker,s.exchange,s.security_type,m.first_day,m.last_day,
       coalesce(m.canonical_rows,0) AS canonical_rows,
       coalesce(m.last_day >= m.first_day + interval '5 years',false) AS spans_five_years,
       coalesce(m.missing_adjusted,0) AS missing_adjusted,
       coalesce(m.unverified_basis,0) AS unverified_basis,
       coalesce(m.duplicate_sessions,0) AS duplicate_sessions,
       coalesce(m.missing_lineage,0) AS missing_lineage,
       coalesce(f.canonical_statements,0) AS canonical_statements,
       coalesce(f.no_publication,0) AS no_publication,
       coalesce(f.point_in_time,0) AS point_in_time
FROM securities s
LEFT JOIN market m ON m.security_id=s.id
LEFT JOIN financial f ON f.security_id=s.id
ORDER BY s.symbol
