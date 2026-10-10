-- Only the superseded NEWS AI table. Refuse data loss; no CASCADE.
CREATE OR REPLACE VIEW vw_company_news_latest AS
WITH eligible AS (
 SELECT r.*,g.request_metadata
 FROM llm_results r JOIN llm_runs g ON g.id=r.llm_run_id
 JOIN llm_prompt_templates t ON t.id=r.prompt_template_id AND t.enabled
 JOIN news_articles a ON a.id=r.news_article_id
 WHERE r.is_current AND r.validation_round_id IS NOT NULL AND g.status='SUCCESS'
 AND g.request_metadata->'input'->'article'->>'content_hash'=a.content_hash
 AND g.request_metadata->'input'->'article'->>'title'=a.title
 AND g.request_metadata->'input'->'article'->>'sapo'=coalesce(a.sapo,'')
 AND g.request_metadata->'input'->'article'->>'url'=a.canonical_url
 AND (g.request_metadata->'input'->'article'->>'published_at')::timestamptz=a.published_at
 AND jsonb_array_length(g.request_metadata->'input'->'article'->'companies')=
     (SELECT count(*) FROM news_article_companies n WHERE n.news_article_id=a.id)
 AND NOT EXISTS (
   SELECT 1 FROM news_article_companies n JOIN companies c ON c.id=n.company_id
   LEFT JOIN securities s ON s.id=n.security_id WHERE n.news_article_id=a.id AND NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(g.request_metadata->'input'->'article'->'companies') x
    WHERE x->>'company_id'=n.company_id::text AND (x->>'security_id') IS NOT DISTINCT FROM n.security_id::text
    AND x->>'name'=c.legal_name AND (x->>'symbol') IS NOT DISTINCT FROM s.symbol
    AND x->>'match_method'=n.match_method AND (x->'match_evidence') IS NOT DISTINCT FROM coalesce(n.match_evidence,'null'::jsonb)))
 AND (SELECT count(*) FROM validation_rules vr WHERE vr.is_active AND vr.data_domain='LLM_OUTPUT'
      AND vr.severity IN ('ERROR','CRITICAL')
      AND vr.code IN ('LLM_RESPONSE_READY','LLM_RESPONSE_SCHEMA','LLM_SOURCE_ID','LLM_EVIDENCE_QUOTES',
       'LLM_COMPANY_REFERENCES','LLM_ANALYSIS_COMPLETENESS','LLM_COMPANY_IMPACT_UNIQUE','LLM_SOURCE_SNAPSHOT','LLM_PROMPT_SNAPSHOT'))=9
 AND NOT EXISTS (
   SELECT 1 FROM validation_rules vr WHERE vr.is_active AND vr.data_domain='LLM_OUTPUT'
   AND vr.rule_config->'tasks' ? r.task_code AND NOT EXISTS (
    SELECT 1 FROM validation_results v WHERE v.llm_run_id=g.id AND v.validation_round_id=r.validation_round_id
    AND v.validation_rule_id=vr.id AND v.rule_snapshot->'config'=vr.rule_config
    AND v.rule_snapshot->>'executor'=vr.executor_key AND v.rule_snapshot->>'severity'=vr.severity))
 AND NOT EXISTS (SELECT 1 FROM validation_results v WHERE v.llm_run_id=g.id
   AND v.validation_round_id=r.validation_round_id AND v.result_status='FAIL' AND v.severity IN ('ERROR','CRITICAL'))
)
SELECT nac.company_id,c.legal_name,nac.security_id,na.id AS news_article_id,
 na.title,na.canonical_url,na.published_at,na.data_source_id,summary.overview AS summary,
 NULL::varchar(20) AS sentiment_label,NULL::numeric(7,6) AS sentiment_score,
 (impact.value->>'direction')::varchar(20) AS impact_direction,
 (impact.value->>'confidence')::numeric(6,5) AS confidence_score
FROM news_article_companies nac
JOIN companies c ON c.id=nac.company_id JOIN news_articles na ON na.id=nac.news_article_id
LEFT JOIN eligible summary ON summary.news_article_id=na.id AND summary.task_code='NEWS_SUMMARY'
LEFT JOIN eligible detail ON detail.news_article_id=na.id AND detail.task_code='NEWS_DETAIL'
LEFT JOIN LATERAL (SELECT value FROM jsonb_array_elements(coalesce(detail.result_json->'company_impacts','[]'::jsonb))
 WHERE value->>'company_id'=nac.company_id::text LIMIT 1) impact ON true
WHERE na.dedup_status<>'DUPLICATE' AND NOT na.is_deleted_source;

DO $$ BEGIN
 IF to_regclass('news_ai_analyses') IS NOT NULL THEN
  LOCK TABLE news_ai_analyses IN ACCESS EXCLUSIVE MODE;
  IF EXISTS (SELECT 1 FROM news_ai_analyses) THEN
   RAISE EXCEPTION 'news_ai_analyses contains data; migrate it before removal';
  END IF;
  DROP TABLE news_ai_analyses;
 END IF;
END $$;
