-- Preserve company-only links (security_id IS NULL) while allowing several
-- securities of the same company to be attached to one article.
BEGIN;

ALTER TABLE news_article_companies ADD COLUMN IF NOT EXISTS id uuid;
UPDATE news_article_companies SET id = gen_random_uuid() WHERE id IS NULL;
ALTER TABLE news_article_companies ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE news_article_companies ALTER COLUMN id SET NOT NULL;

DO $$
DECLARE
    current_primary_key text;
BEGIN
    SELECT pg_get_constraintdef(oid)
      INTO current_primary_key
      FROM pg_constraint
     WHERE conrelid = 'news_article_companies'::regclass
       AND contype = 'p';

    IF current_primary_key IS NOT NULL AND current_primary_key <> 'PRIMARY KEY (id)' THEN
        ALTER TABLE news_article_companies DROP CONSTRAINT news_article_companies_pkey;
        current_primary_key := NULL;
    END IF;

    IF current_primary_key IS NULL THEN
        ALTER TABLE news_article_companies
            ADD CONSTRAINT news_article_companies_pkey PRIMARY KEY (id);
    END IF;
END $$;

-- NULLS NOT DISTINCT also prevents duplicate company-only links.
CREATE UNIQUE INDEX IF NOT EXISTS uq_news_article_companies_article_company_security
    ON news_article_companies (news_article_id, company_id, security_id)
    NULLS NOT DISTINCT;

COMMIT;
