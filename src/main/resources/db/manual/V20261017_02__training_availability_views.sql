-- Observation time is never a proxy for historical publication availability.
CREATE OR REPLACE VIEW vw_training_metric_observations_v1 AS
SELECT m.id,m.company_id,m.security_id,se.symbol,d.code metric_code,m.as_of_date,m.value,d.unit,
 m.data_source_id,m.raw_payload_id,m.data_version_id,m.is_canonical,m.is_derived,m.calculation_version,
 m.quality_status,m.input_snapshot,coalesce(r.fetched_at,m.created_at) system_first_seen_at,
 coalesce(r.fetched_at,m.created_at) known_at,false AS historical_publication_verified
FROM financial_metrics m JOIN metric_definitions d ON d.id=m.metric_definition_id
LEFT JOIN securities se ON se.id=m.security_id LEFT JOIN raw_payloads r ON r.id=m.raw_payload_id
JOIN data_versions v ON v.id=m.data_version_id
WHERE m.quality_status='VALID' AND v.status='ACTIVATED';

CREATE OR REPLACE VIEW vw_training_macro_observations_v1 AS
SELECT o.id,s.code,s.country_code,s.frequency,s.unit,o.observation_date,o.value,s.data_source_id,
 o.raw_payload_id,o.data_version_id,r.fetched_at system_first_seen_at,r.fetched_at known_at,
 false AS historical_publication_verified,r.payload->>'source_url' envelope_source_url,
 (SELECT series->>'source_url' FROM jsonb_array_elements(r.payload->'data') series WHERE series->>'code'=s.code LIMIT 1) series_source_url
FROM macro_observations o JOIN macro_series s ON s.id=o.macro_series_id
JOIN raw_payloads r ON r.id=o.raw_payload_id JOIN data_versions v ON v.id=o.data_version_id
WHERE v.status='ACTIVATED';
