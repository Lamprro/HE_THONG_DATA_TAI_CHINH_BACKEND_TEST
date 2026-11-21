SELECT 'canonical_market_security_date' AS domain,count(*) AS duplicate_groups,coalesce(sum(n-1),0) AS excess_rows
FROM (SELECT count(*) n FROM vw_training_market_observations_v1 WHERE is_canonical GROUP BY security_id,trading_date HAVING count(*)>1) d
UNION ALL SELECT 'canonical_financial_statement_item',count(*),coalesce(sum(n-1),0)
FROM (SELECT count(*) n FROM vw_training_statement_inputs_v1 GROUP BY statement_id,item_code HAVING count(*)>1) d
UNION ALL SELECT 'valid_canonical_metric_key',count(*),coalesce(sum(n-1),0)
FROM (SELECT count(*) n FROM vw_training_metric_observations_v1 WHERE is_canonical GROUP BY company_id,security_id,metric_code,as_of_date HAVING count(*)>1) d
