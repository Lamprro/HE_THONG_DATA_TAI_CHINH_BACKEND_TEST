SELECT 'financial_statements' AS table_name,count(*) AS rows FROM financial_statements
UNION ALL SELECT 'financial_statement_items',count(*) FROM financial_statement_items
UNION ALL SELECT 'financial_metrics',count(*) FROM financial_metrics
UNION ALL SELECT 'market_prices',count(*) FROM market_prices
UNION ALL SELECT 'index_prices',count(*) FROM index_prices
UNION ALL SELECT 'macro_observations',count(*) FROM macro_observations
UNION ALL SELECT 'news_articles',count(*) FROM news_articles
UNION ALL SELECT 'news_article_companies',count(*) FROM news_article_companies
UNION ALL SELECT 'security_foreign_observations',count(*) FROM security_foreign_observations
UNION ALL SELECT 'security_index_memberships',count(*) FROM security_index_memberships
