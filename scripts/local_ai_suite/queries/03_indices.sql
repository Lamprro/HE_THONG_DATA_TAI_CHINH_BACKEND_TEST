SELECT p.id AS source_id,i.code AS ticker,(p.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS trading_date,
p.open_value AS open,p.high_value AS high,p.low_value AS low,p.close_value AS close,p.volume,p.trading_value,
p.price_timestamp,p.interval_code,p.is_canonical,p.data_source_id,p.raw_payload_id,p.data_version_id,v.status AS version_status,p.created_at
FROM index_prices p JOIN market_indices i ON i.id=p.market_index_id JOIN data_versions v ON v.id=p.data_version_id
WHERE (p.is_canonical OR p.data_source_id=20) AND p.interval_code='1d' AND v.status='ACTIVATED' ORDER BY i.code,p.price_timestamp,p.id
