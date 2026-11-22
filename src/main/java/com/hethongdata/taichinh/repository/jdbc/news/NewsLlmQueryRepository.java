package com.hethongdata.taichinh.repository.jdbc.news;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class NewsLlmQueryRepository {
  private final JdbcTemplate db;

  public NewsLlmQueryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> findArticleById(UUID id) {
    return db.queryForList("SELECT * FROM news_articles WHERE id=?", id);
  }

  public List<Map<String, Object>> findCompaniesByArticleId(UUID articleId) {
    return db.queryForList(
        "SELECT s.symbol,s.exchange FROM news_article_companies c JOIN securities s ON s.id=c.security_id WHERE c.news_article_id=?",
        articleId);
  }

  public void queryContextCompanies(UUID articleId, RowCallbackHandler rch) {
    db.query(
        """
        SELECT n.company_id,n.security_id,c.legal_name,s.symbol,n.match_method,n.match_evidence
        FROM news_article_companies n JOIN companies c ON c.id=n.company_id
        LEFT JOIN securities s ON s.id=n.security_id WHERE n.news_article_id=?
        ORDER BY n.company_id,n.security_id NULLS FIRST,n.id
        """,
        rch, articleId);
  }

  public List<UUID> findPendingValidationRuns(int limit) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE status='PENDING_VALIDATION' AND news_article_id IS NOT NULL AND task_code IN ('NEWS_SUMMARY','NEWS_DETAIL','NEWS_FINANCIAL_FACTS') ORDER BY created_at,id LIMIT ?",
        UUID.class, Math.max(1, Math.min(50, limit)));
  }

  public List<Map<String, Object>> findValidationFeedback(UUID article, String task, String inputHash) {
    return db.queryForList(
        """
        SELECT response_text,validation_errors::text AS errors FROM llm_runs
        WHERE news_article_id=? AND task_code=? AND input_hash=? AND status='REJECTED'
        AND response_text IS NOT NULL ORDER BY created_at DESC LIMIT 1
        """,
        article, task, inputHash);
  }

  public List<UUID> findCandidates(
      String routingKey,
      String summaryPolicy,
      String detailPolicy,
      String financialPolicy,
      int limit) {
    return db.queryForList(
        """
        SELECT a.id FROM news_articles a WHERE a.dedup_status='UNIQUE'
        AND NOT a.is_deleted_source AND length(a.content_text)>=200
        AND NOT coalesce((a.metadata->>'llm_excluded')::boolean,false)
        AND NOT coalesce((a.metadata->>'is_test')::boolean,false)
        AND a.canonical_url !~* '(real-db-|localhost|example.com|/test/)'
        AND NOT EXISTS (SELECT 1 FROM llm_runs x WHERE x.news_article_id=a.id AND x.status='RUNNING'
            AND x.created_at>now()-interval '10 minutes')
        AND NOT EXISTS (SELECT 1 FROM llm_runs x WHERE x.news_article_id=a.id AND x.status='PENDING_VALIDATION')
        AND NOT EXISTS (SELECT 1 FROM llm_runs x WHERE x.news_article_id=a.id
            AND x.created_at>now()-interval '1 hour' AND x.status IN ('FAILED','REJECTED'))
        AND NOT EXISTS (SELECT 1 FROM llm_runs x JOIN llm_prompt_templates t ON t.id=x.prompt_template_id AND t.enabled
            WHERE x.news_article_id=a.id AND x.created_at>=a.updated_at AND coalesce(x.request_metadata->>'routing_key',x.model_name)=?
            AND x.request_metadata->>'validation_policy'=CASE x.task_code WHEN 'NEWS_SUMMARY' THEN ? WHEN 'NEWS_DETAIL' THEN ? ELSE ? END
            AND x.status IN ('FAILED','REJECTED') GROUP BY x.task_code,x.input_hash HAVING count(*)>=3)
        AND (EXISTS (SELECT 1 FROM llm_prompt_templates t WHERE t.enabled AND t.input_domain='NEWS'
            AND t.task_code IN ('NEWS_SUMMARY','NEWS_DETAIL') AND NOT EXISTS
            (SELECT 1 FROM llm_results r WHERE r.news_article_id=a.id AND r.prompt_template_id=t.id
             AND r.is_current AND r.created_at>=a.updated_at AND r.validation_round_id IS NOT NULL
             AND r.validation_policy_hash=CASE t.task_code WHEN 'NEWS_SUMMARY' THEN ? ELSE ? END
             AND EXISTS (SELECT 1 FROM llm_runs g WHERE g.id=r.llm_run_id AND coalesce(g.request_metadata->>'routing_key',g.model_name)=?) AND NOT EXISTS
             (SELECT 1 FROM news_article_companies n WHERE n.news_article_id=a.id AND n.created_at>r.created_at)))
            OR EXISTS (SELECT 1 FROM llm_results d JOIN llm_prompt_templates dt ON dt.id=d.prompt_template_id AND dt.enabled
                WHERE d.news_article_id=a.id AND d.task_code='NEWS_DETAIL' AND d.is_current
                AND EXISTS (SELECT 1 FROM llm_prompt_templates ft WHERE ft.task_code='NEWS_FINANCIAL_FACTS' AND ft.enabled)
                AND ((d.result_json->>'contains_financial_figures')::boolean OR (d.result_json->>'contains_forecasts')::boolean)
                AND NOT EXISTS (SELECT 1 FROM llm_results f JOIN llm_prompt_templates t ON t.id=f.prompt_template_id AND t.enabled
                    WHERE f.news_article_id=a.id AND f.task_code='NEWS_FINANCIAL_FACTS' AND f.is_current AND f.source_hash=d.source_hash
                    AND f.validation_round_id IS NOT NULL AND f.validation_policy_hash=?
                    AND EXISTS (SELECT 1 FROM llm_runs g WHERE g.id=f.llm_run_id AND coalesce(g.request_metadata->>'routing_key',g.model_name)=?))))
        ORDER BY a.published_at DESC,a.id LIMIT ?
        """,
        UUID.class,
        routingKey,
        summaryPolicy,
        detailPolicy,
        financialPolicy,
        summaryPolicy,
        detailPolicy,
        routingKey,
        financialPolicy,
        routingKey,
        Math.max(1, Math.min(20, limit)));
  }
}
