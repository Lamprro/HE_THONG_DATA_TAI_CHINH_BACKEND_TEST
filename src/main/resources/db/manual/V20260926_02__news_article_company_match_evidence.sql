-- Evidence belongs to each article-company/security relationship, not to the whole article.
BEGIN;

ALTER TABLE news_article_companies
    ADD COLUMN IF NOT EXISTS match_evidence jsonb;

COMMIT;
