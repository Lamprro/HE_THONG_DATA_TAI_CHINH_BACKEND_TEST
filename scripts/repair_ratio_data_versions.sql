BEGIN;

-- Preview only candidates whose entire source run consists of RATIO payloads.
SELECT dv.id, dv.ingestion_run_id, dv.data_domain, dv.status, ij.code, ij.dataset_type
FROM data_versions dv
JOIN ingestion_runs ir ON ir.id = dv.ingestion_run_id
JOIN ingestion_jobs ij ON ij.id = ir.ingestion_job_id
WHERE dv.data_domain = 'MARKET_PRICE' AND dv.status = 'ACTIVE'
  AND ij.dataset_type = 'FINANCIAL_METRIC'
  AND EXISTS (SELECT 1 FROM raw_payloads rp WHERE rp.ingestion_run_id = dv.ingestion_run_id)
  AND NOT EXISTS (SELECT 1 FROM raw_payloads rp WHERE rp.ingestion_run_id = dv.ingestion_run_id AND rp.entity_type <> 'RATIO');

UPDATE data_versions dv
SET data_domain = 'FINANCIAL_METRIC'
FROM ingestion_runs ir, ingestion_jobs ij
WHERE ir.id = dv.ingestion_run_id AND ij.id = ir.ingestion_job_id
  AND dv.data_domain = 'MARKET_PRICE' AND dv.status = 'ACTIVE'
  AND ij.dataset_type = 'FINANCIAL_METRIC'
  AND EXISTS (SELECT 1 FROM raw_payloads rp WHERE rp.ingestion_run_id = dv.ingestion_run_id)
  AND NOT EXISTS (SELECT 1 FROM raw_payloads rp WHERE rp.ingestion_run_id = dv.ingestion_run_id AND rp.entity_type <> 'RATIO');

SELECT count(*) AS repaired_active_ratio_versions FROM data_versions dv
JOIN ingestion_runs ir ON ir.id = dv.ingestion_run_id
JOIN ingestion_jobs ij ON ij.id = ir.ingestion_job_id
WHERE dv.data_domain = 'FINANCIAL_METRIC' AND dv.status = 'ACTIVE' AND ij.dataset_type = 'FINANCIAL_METRIC';

COMMIT;
