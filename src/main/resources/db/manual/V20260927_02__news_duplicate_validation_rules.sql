-- Keep duplicate URLs as warnings so the article build can add a missing source link.
INSERT INTO validation_rules
    (code, name, data_domain, severity, rule_type, rule_config, description,
     is_active, executor_key, created_at, updated_at)
VALUES
    ('NEWS_URL_PREVIOUSLY_FETCHED', 'News URL already fetched', 'NEWS', 'WARNING',
     'UNIQUE', '{"dataField":"data","fields":["url","link","href"]}'::jsonb,
     'Listed URL already has NEWS_DATA; fetch job reuses its content.', true,
     'NEWS_URL_PREVIOUSLY_FETCHED', now(), now()),
    ('NEWS_DATA_URL_PREVIOUSLY_SEEN', 'News article data URL already seen', 'NEWS_DATA',
     'WARNING', 'UNIQUE', '{}'::jsonb,
     'Another NEWS_DATA raw payload has this requested URL.', true,
     'NEWS_DATA_URL_PREVIOUSLY_SEEN', now(), now()),
    ('NEWS_DATA_ARTICLE_EXTRACTED', 'News publisher article extracted', 'NEWS_DATA',
     'WARNING', 'NOT_NULL', '{}'::jsonb,
     'Record extraction failures as warnings so valid articles in the same fetch batch can proceed; the article builder skips each invalid payload individually.', true,
     'NEWS_DATA_ARTICLE_EXTRACTED', now(), now())
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    data_domain = EXCLUDED.data_domain,
    severity = EXCLUDED.severity,
    rule_type = EXCLUDED.rule_type,
    rule_config = EXCLUDED.rule_config,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    executor_key = EXCLUDED.executor_key,
    updated_at = now();
