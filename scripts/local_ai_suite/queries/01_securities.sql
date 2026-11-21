SELECT s.id AS security_id,s.company_id,s.symbol AS ticker,s.exchange,s.security_type,s.currency,s.is_active,s.listed_date,s.delisted_date
FROM securities s ORDER BY s.symbol
