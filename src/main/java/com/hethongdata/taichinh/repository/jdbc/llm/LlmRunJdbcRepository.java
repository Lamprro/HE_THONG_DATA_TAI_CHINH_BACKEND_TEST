package com.hethongdata.taichinh.repository.jdbc.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.service.llm.LlmGateway;
import com.hethongdata.taichinh.service.llm.LlmJson;
import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LlmRunJdbcRepository {
  private final JdbcTemplate db;

  public LlmRunJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void lock(UUID article, String task) {
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", article + ":" + task);
  }

  public List<Map<String, Object>> findCachedResult(UUID article, String task, String inputHash) {
    return db.queryForList(
        "SELECT id,llm_run_id FROM llm_results WHERE news_article_id=? AND task_code=? AND input_hash=? AND is_current",
        article, task, inputHash);
  }

  public void expireRunningLeases(UUID article, String task) {
    db.update(
        """
        UPDATE llm_runs SET status='FAILED',error_message='RUN_LEASE_EXPIRED',finished_at=now()
        WHERE news_article_id=? AND task_code=? AND status='RUNNING' AND created_at < now()-interval '10 minutes'
        """,
        article, task);
  }

  public List<UUID> findActiveRuns(UUID article, String task) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE news_article_id=? AND task_code=? AND status IN ('RUNNING','PENDING_VALIDATION')",
        UUID.class, article, task);
  }

  public Integer countFailedAttempts(UUID article, String task, String inputHash) {
    return db.queryForObject(
        "SELECT count(*) FROM llm_runs WHERE news_article_id=? AND task_code=? AND input_hash=? AND status IN ('FAILED','REJECTED')",
        Integer.class, article, task, inputHash);
  }

  public void insertRunningRun(
      UUID run,
      String operation,
      String model,
      String version,
      String inputHash,
      String metadataJson,
      UUID templateId,
      String task,
      UUID article,
      String requestJson) {
    db.update(
        """
        INSERT INTO llm_runs(id,operation_type,provider,model_name,prompt_version,status,input_hash,request_metadata,
            response_metadata,created_at,prompt_template_id,task_code,news_article_id,request_payload)
        VALUES (?,?,'GEMINI',?,?,'RUNNING',?,?::jsonb,'{}'::jsonb,now(),?,?,?,?::jsonb)
        """,
        run, operation, model, version, inputHash, metadataJson, templateId, task, article, requestJson);
  }

  public boolean stageResponse(
      UUID run,
      String rawJson,
      String outputText,
      int httpStatus,
      Integer inputTokens,
      Integer outputTokens,
      int latency,
      String selectedModel,
      String readinessJson) {
    return db.update(
        """
        UPDATE llm_runs SET status='PENDING_VALIDATION',response_payload=?::jsonb,response_text=?,http_status=?,
            input_tokens=?,output_tokens=?,latency_ms=?,model_name=coalesce(?,model_name),validation_errors=?::jsonb
        WHERE id=? AND status='RUNNING'
        """,
        rawJson, outputText, httpStatus, inputTokens, outputTokens, latency, selectedModel, readinessJson, run) == 1;
  }

  public String findStatusForUpdate(UUID run) {
    return db.queryForObject("SELECT status FROM llm_runs WHERE id=? FOR UPDATE", String.class, run);
  }

  public List<UUID> findCurrentResultIdsByRunId(UUID run) {
    return db.queryForList("SELECT id FROM llm_results WHERE llm_run_id=? AND is_current", UUID.class, run);
  }

  public void lockNewsArticleForShare(UUID article) {
    db.queryForList("SELECT id FROM news_articles WHERE id=? FOR SHARE", article);
  }

  public boolean isTemplateActive(UUID templateId, String checksum) {
    return !db.queryForList(
        "SELECT id FROM llm_prompt_templates WHERE id=? AND enabled AND checksum=?",
        UUID.class, templateId, checksum).isEmpty();
  }

  public String findValidationPolicy(UUID run) {
    return db.queryForObject("SELECT request_metadata->>'validation_policy' FROM llm_runs WHERE id=?", String.class, run);
  }

  public boolean isArticleRecoveryAssessmentPartial(UUID article) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT coalesce(metadata->>'recovery_assessment'='PARTIAL',false) FROM news_articles WHERE id=?",
            Boolean.class, article));
  }

  public void updateRunFinished(
      UUID run,
      String status,
      String rawJson,
      String outputText,
      String errorsJson,
      int httpStatus,
      Integer inputTokens,
      Integer outputTokens,
      int latency,
      String errorMessage,
      String selectedModel,
      UUID validationRoundId) {
    db.update(
        """
        UPDATE llm_runs SET status=?,response_payload=?::jsonb,response_text=?,validation_errors=?::jsonb,
            http_status=?,input_tokens=?,output_tokens=?,latency_ms=?,finished_at=now(),error_message=?,
            model_name=coalesce(?,model_name),validation_round_id=? WHERE id=?
        """,
        status, rawJson, outputText, errorsJson, httpStatus, inputTokens, outputTokens, latency, errorMessage,
        selectedModel, validationRoundId, run);
  }

  public void retireResults(UUID article, String task) {
    db.update("UPDATE llm_results SET is_current=false WHERE news_article_id=? AND task_code=? AND is_current", article, task);
  }

  public void insertResult(
      UUID result,
      UUID run,
      UUID templateId,
      String task,
      UUID article,
      String sourceHash,
      String inputHash,
      String schemaVersion,
      String overview,
      String outputJson,
      String qualityStatus,
      UUID validationRoundId,
      String validationPolicyHash) {
    db.update(
        """
        INSERT INTO llm_results(id,llm_run_id,prompt_template_id,task_code,source_domain,news_article_id,
            source_hash,input_hash,schema_version,overview,result_json,quality_status,validation_round_id,validation_policy_hash)
        VALUES (?,?,?,?,'NEWS',?,?,?,?,?,?::jsonb,?,?,?)
        """,
        result, run, templateId, task, article, sourceHash, inputHash, schemaVersion, overview, outputJson,
        qualityStatus, validationRoundId, validationPolicyHash);
  }

  public void failRun(UUID run, String category, int latency) {
    db.update("UPDATE llm_runs SET status='FAILED',finished_at=now(),error_message=?,latency_ms=? WHERE id=? AND status='RUNNING'",
        category, latency, run);
  }

  public List<Map<String, Object>> findPassingResults(UUID article, String sourceHash) {
    return db.queryForList(
        """
        SELECT r.id,r.task_code,r.result_json,r.quality_status,r.created_at,r.validation_policy_hash FROM llm_results r
        JOIN llm_prompt_templates t ON t.id=r.prompt_template_id AND t.enabled
        WHERE r.news_article_id=? AND r.is_current AND r.source_hash=? AND r.validation_round_id IS NOT NULL
            AND EXISTS (SELECT 1 FROM validation_results v WHERE v.llm_run_id=r.llm_run_id
                AND v.validation_round_id=r.validation_round_id)
            AND NOT EXISTS (SELECT 1 FROM validation_results v WHERE v.llm_run_id=r.llm_run_id
                AND v.validation_round_id=r.validation_round_id AND v.result_status='FAIL' AND v.severity IN ('ERROR','CRITICAL'))
        ORDER BY r.task_code
        """,
        article, sourceHash);
  }

  public String findTaskCode(UUID run) {
    return db.queryForObject("SELECT task_code FROM llm_runs WHERE id=?", String.class, run);
  }

  public List<Map<String, Object>> findRunById(UUID run) {
    return db.queryForList("SELECT * FROM llm_runs WHERE id=?", run);
  }

  public List<Map<String, Object>> findRunByIdForUpdate(UUID run) {
    return db.queryForList("SELECT * FROM llm_runs WHERE id=? FOR UPDATE", run);
  }

  public List<LlmPromptCatalog.Template> findTemplateById(UUID templateId, String task, LlmJson json) {
    return db.query(
        "SELECT * FROM llm_prompt_templates WHERE id=?",
        (rs, n) -> new LlmPromptCatalog.Template(
            rs.getObject("id", UUID.class),
            task,
            rs.getString("version"),
            rs.getString("system_prompt"),
            json.read(rs.getString("request_schema")),
            json.read(rs.getString("response_schema")),
            rs.getString("checksum")),
        templateId);
  }

  public boolean countActiveTemplate(UUID templateId, String checksum) {
    Integer count = db.queryForObject(
        "SELECT count(*) FROM llm_prompt_templates WHERE id=? AND enabled AND checksum=?",
        Integer.class, templateId, checksum);
    return count != null && count == 1;
  }

  public void updateRunValidationRound(UUID run, UUID roundId) {
    db.update("UPDATE llm_runs SET validation_round_id=? WHERE id=?", roundId, run);
  }

  public void updateResultOnSuccess(UUID run, UUID roundId, String policy, String inputHash) {
    db.update(
        "UPDATE llm_results SET validation_round_id=?,validation_policy_hash=?,input_hash=? WHERE llm_run_id=?",
        roundId, policy, inputHash, run);
  }

  public void demoteResultAndFailRun(UUID run, String status, String errorsJson, String errorMessage) {
    db.update("UPDATE llm_results SET is_current=false WHERE llm_run_id=?", run);
    db.update("UPDATE llm_runs SET status=?,validation_errors=?::jsonb,error_message=? WHERE id=?",
        status, errorsJson, errorMessage, run);
  }

  public Integer countExistingResultsForArticle(UUID run, UUID article, String task) {
    return db.queryForObject(
        "SELECT count(*) FROM llm_results WHERE llm_run_id=? OR (news_article_id=? AND task_code=? AND is_current)",
        Integer.class, run, article, task);
  }

  public void updateRunValidationSuccess(UUID run) {
    db.update("UPDATE llm_runs SET status='SUCCESS',validation_errors='[]'::jsonb,error_message=null WHERE id=?", run);
  }

  public Optional<String> findRunJson(UUID run) {
    return db.query("SELECT row_to_json(r)::text FROM llm_runs r WHERE id=?", (rs, n) -> rs.getString(1), run)
        .stream().findFirst();
  }

  public List<String> findValidationResultsJson(UUID run) {
    return db.query(
        "SELECT row_to_json(v)::text FROM validation_results v WHERE llm_run_id=? ORDER BY checked_at,rule_code",
        (rs, n) -> rs.getString(1), run);
  }

  public void appendAttempt(LlmJson json, UUID run, JsonNode request, LlmGateway.Attempt attempt) {
    appendAttempt(this.db, json, run, request, attempt);
  }

  public List<JsonNode> readAttempts(LlmJson json, UUID run) {
    return readAttempts(this.db, json, run);
  }

  public static void appendAttempt(
      JdbcTemplate db, LlmJson json, UUID run, JsonNode request, LlmGateway.Attempt attempt) {
    if (request == null || attempt.number() < 1 || attempt.latencyMs() < 0 || attempt.model() == null)
      throw new IllegalArgumentException("Invalid attempt audit");
    var entry = json.mapper().createObjectNode();
    entry.put("id", UUID.randomUUID().toString())
        .put("llm_run_id", run.toString())
        .put("attempt_no", attempt.number())
        .put("model_name", attempt.model())
        .put("http_status", attempt.httpStatus())
        .put("error_category", attempt.error())
        .put("response_body", attempt.rawBody())
        .put("response_text", attempt.outputText())
        .put("input_tokens", attempt.inputTokens())
        .put("output_tokens", attempt.outputTokens())
        .put("latency_ms", attempt.latencyMs())
        .put("created_at", java.time.Instant.now().toString());
    entry.set("request_payload", request);
    int updated = db.update(
        """
        UPDATE llm_runs SET attempts=attempts || jsonb_build_array(?::jsonb)
        WHERE id=? AND NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(attempts) a WHERE (a->>'attempt_no')::integer=?)
        """,
        entry.toString(), run, attempt.number());
    if (updated != 1) throw new IllegalStateException("Run missing or duplicate attempt number: " + run);
  }

  public static List<JsonNode> readAttempts(JdbcTemplate db, LlmJson json, UUID run) {
    return db.query(
        """
        SELECT a::text FROM llm_runs r CROSS JOIN LATERAL jsonb_array_elements(r.attempts) a
        WHERE r.id=? ORDER BY (a->>'attempt_no')::integer
        """,
        (rs, n) -> json.read(rs.getString(1)), run);
  }
}
