package com.hethongdata.taichinh.repository.jdbc.news;

import com.hethongdata.taichinh.dto.news.NewsRecoveryDtos.Candidate;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NewsRecoveryJdbcRepository {
  private final JdbcTemplate db;

  public NewsRecoveryJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<String> findArticleJson(UUID id) {
    return db
        .query(
            "SELECT"
                + " jsonb_build_object('id',id,'url',canonical_url,'title',title,'sapo',sapo,'content_text',content_text,'content_hash',content_hash,'author',author,'published_at',published_at,'updated_at',updated_at,'dedup_status',dedup_status,'is_deleted_source',is_deleted_source,'metadata',metadata)::text"
                + " FROM news_articles WHERE id=?",
            (r, n) -> r.getString(1),
            id)
        .stream()
        .findFirst();
  }

  public List<Candidate> findCandidates(int limit) {
    return db.query(
        """
        SELECT id,title,canonical_url,coalesce(length(content_text),0) FROM news_articles a
        WHERE dedup_status='UNIQUE' AND NOT is_deleted_source AND coalesce(length(content_text),0)<200
        AND NOT coalesce((metadata->>'is_test')::boolean,false) AND NOT coalesce((metadata->>'llm_excluded')::boolean,false)
        AND canonical_url !~* '(real-db-|localhost|example.com|/test/)'
        AND NOT EXISTS (SELECT 1 FROM llm_runs r WHERE r.news_article_id=a.id AND r.task_code='NEWS_CONTENT_RECOVERY'
          AND (r.status='PENDING_VALIDATION' OR r.status='RUNNING' AND r.created_at>now()-interval '15 minutes'
            OR r.status IN ('FAILED','REJECTED') AND r.created_at>now()-interval '1 hour'))
        AND (SELECT count(*) FROM llm_runs r WHERE r.news_article_id=a.id AND r.task_code='NEWS_CONTENT_RECOVERY'
          AND r.status IN ('FAILED','REJECTED') AND r.created_at>=a.updated_at)<3
        ORDER BY published_at DESC NULLS LAST,id LIMIT ?
        """,
        (r, n) ->
            new Candidate(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getInt(4)),
        Math.max(1, Math.min(50, limit)));
  }

  public void lockArticleForUpdate(UUID article) {
    db.queryForList("SELECT id FROM news_articles WHERE id=? FOR UPDATE", article);
  }

  public void lockArticleForShare(UUID article) {
    db.queryForList("SELECT id FROM news_articles WHERE id=? FOR SHARE", article);
  }

  public void expireRunningLeases(UUID article, String task) {
    db.update(
        "UPDATE llm_runs SET"
            + " status='FAILED',error_message='RECOVERY_LEASE_EXPIRED',finished_at=now() WHERE"
            + " news_article_id=? AND task_code=? AND status='RUNNING' AND"
            + " created_at<now()-interval '15 minutes'",
        article,
        task);
  }

  public List<UUID> findActiveRuns(UUID article, String task) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE news_article_id=? AND task_code=? AND status IN"
            + " ('RUNNING','PENDING_VALIDATION')",
        UUID.class,
        article,
        task);
  }

  public Integer countFailures(UUID article, String task, String inputHash) {
    return db.queryForObject(
        "SELECT count(*) FROM llm_runs WHERE news_article_id=? AND task_code=? AND input_hash=?"
            + " AND status IN ('FAILED','REJECTED')",
        Integer.class,
        article,
        task,
        inputHash);
  }

  public void insertRun(
      UUID run,
      String model,
      String version,
      String inputHash,
      String metadataJson,
      UUID templateId,
      String task,
      UUID article) {
    db.update(
        "INSERT INTO"
            + " llm_runs(id,operation_type,provider,model_name,prompt_version,status,input_hash,request_metadata,response_metadata,created_at,prompt_template_id,task_code,news_article_id)"
            + " VALUES (?,'NEWS_PARSE','GEMINI',?,?,'RUNNING',?,?::jsonb,'{}'::jsonb,now(),?,?,?)",
        run,
        model,
        version,
        inputHash,
        metadataJson,
        templateId,
        task,
        article);
  }

  public List<Map<String, Object>> findRunById(UUID id, String task) {
    return db.queryForList("SELECT * FROM llm_runs WHERE id=? AND task_code=?", id, task);
  }

  public List<UUID> findPendingRunIds(String task, int limit) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE task_code=? AND status='PENDING_VALIDATION' ORDER BY"
            + " created_at,id LIMIT ?",
        UUID.class,
        task,
        Math.max(1, Math.min(50, limit)));
  }

  public boolean checkpoint(UUID run, String requestJson, String metadataJson) {
    return db.update(
        "UPDATE llm_runs SET request_payload=?::jsonb,response_metadata=?::jsonb WHERE id=? AND"
            + " status='RUNNING'",
        requestJson,
        metadataJson,
        run) == 1;
  }

  public void lockRunForUpdate(UUID id) {
    db.queryForList("SELECT id FROM llm_runs WHERE id=? FOR UPDATE", id);
  }

  public void stageResponse(
      UUID id,
      String outputJson,
      String rawBody,
      String metadataJson,
      String errorsJson,
      UUID round,
      int httpStatus,
      String modelName,
      int inputTokens,
      int outputTokens) {
    db.update(
        "UPDATE llm_runs SET"
            + " status='PENDING_VALIDATION',response_text=?,response_payload=?::jsonb,response_metadata=?::jsonb,validation_errors=?::jsonb,validation_round_id=?,http_status=?,model_name=?,input_tokens=?,output_tokens=?"
            + " WHERE id=?",
        outputJson,
        rawBody,
        metadataJson,
        errorsJson,
        round,
        httpStatus,
        modelName,
        inputTokens,
        outputTokens,
        id);
  }

  public void updateTotals(UUID id) {
    db.update(
        """
        UPDATE llm_runs SET
          input_tokens=(SELECT CASE WHEN count(a->>'input_tokens')=0 THEN NULL
                        ELSE least(sum((a->>'input_tokens')::bigint),2147483647) END FROM jsonb_array_elements(attempts) a),
          output_tokens=(SELECT CASE WHEN count(a->>'output_tokens')=0 THEN NULL
                         ELSE least(sum((a->>'output_tokens')::bigint),2147483647) END FROM jsonb_array_elements(attempts) a),
          latency_ms=(SELECT least(coalesce(sum((a->>'latency_ms')::bigint),0),2147483647) FROM jsonb_array_elements(attempts) a)
        WHERE id=?
        """,
        id);
  }

  public void failRun(UUID runId, String reason) {
    db.update(
        "UPDATE llm_runs SET status='FAILED',error_message=?,finished_at=now() WHERE id=? AND"
            + " status='RUNNING'",
        reason,
        runId);
  }

  public void rejectRun(UUID id, String metaJson) {
    db.update(
        "UPDATE llm_runs SET"
            + " status='REJECTED',response_metadata=?::jsonb,finished_at=now(),error_message='ADMIN_REJECTED'"
            + " WHERE id=?",
        metaJson,
        id);
  }

  public void updateValidationErrors(UUID id, String errorsJson, UUID round) {
    db.update(
        "UPDATE llm_runs SET validation_errors=?::jsonb,validation_round_id=? WHERE id=?",
        errorsJson,
        round,
        id);
  }

  public void lockContentHash(String contentHash) {
    db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "news-content:" + contentHash);
  }

  public boolean contentHashExists(String contentHash, UUID excludeArticleId) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM news_articles WHERE content_hash=? AND id<>?)",
            Boolean.class,
            contentHash,
            excludeArticleId));
  }

  public void updateArticle(
      UUID articleId,
      String title,
      String sapo,
      String content,
      String contentHash,
      String author,
      Timestamp publishedAt,
      String metadataJson) {
    db.update(
        "UPDATE news_articles SET"
            + " title=?,sapo=?,content_text=?,content_hash=?,author=coalesce(?,author),published_at=?,metadata=?::jsonb,updated_at=now()"
            + " WHERE id=?",
        title,
        sapo,
        content,
        contentHash,
        author,
        publishedAt,
        metadataJson,
        articleId);
  }

  public void retireCurrentResults(UUID articleId) {
    db.update(
        "UPDATE llm_results SET is_current=false WHERE news_article_id=? AND is_current",
        articleId);
  }

  public void approveRun(UUID id, String metaJson, UUID round) {
    db.update(
        "UPDATE llm_runs SET"
            + " status='SUCCESS',response_metadata=?::jsonb,validation_round_id=?,validation_errors='[]'::jsonb,finished_at=now()"
            + " WHERE id=?",
        metaJson,
        round,
        id);
  }

  public List<String> findValidationRulesJson() {
    return db.query(
        "SELECT"
            + " jsonb_build_object('id',id,'code',code,'executor',executor_key,'severity',severity,'config',rule_config)::text"
            + " FROM validation_rules WHERE data_domain='NEWS_RECOVERY' AND is_active ORDER BY"
            + " code",
        (r, n) -> r.getString(1));
  }

  public void insertValidationResult(
      UUID id,
      long ruleId,
      String code,
      String severity,
      String resultStatus,
      String handlingStatus,
      UUID runId,
      UUID roundId,
      String ruleSnapshotJson,
      String observedValueJson,
      String message) {
    db.update(
        "INSERT INTO"
            + " validation_results(id,validation_rule_id,rule_code,severity,result_status,handling_status,checked_at,validation_target,llm_run_id,validation_round_id,rule_snapshot,observed_value,expected_value,message)"
            + " VALUES (?,?,?,?,?,?,now(),'LLM_OUTPUT',?,?,?::jsonb,?,'PASS',?)",
        id,
        ruleId,
        code,
        severity,
        resultStatus,
        handlingStatus,
        runId,
        roundId,
        ruleSnapshotJson,
        observedValueJson,
        message);
  }
}
