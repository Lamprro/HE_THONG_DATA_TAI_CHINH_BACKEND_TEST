-- The Java news matcher records text-derived article/company links as TEXT_MATCH.
BEGIN;

ALTER TABLE news_article_companies
    DROP CONSTRAINT IF EXISTS news_article_companies_match_method_check;

ALTER TABLE news_article_companies
    ADD CONSTRAINT news_article_companies_match_method_check
    CHECK (match_method IN ('RULE', 'NER', 'LLM', 'MANUAL', 'TEXT_MATCH'));

COMMIT;
