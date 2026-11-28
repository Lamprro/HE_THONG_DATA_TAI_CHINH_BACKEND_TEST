package com.hethongdata.taichinh.repository.jdbc.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.repository.jdbc.llm.LlmRunJdbcRepository;
import com.hethongdata.taichinh.service.llm.LlmGateway;
import com.hethongdata.taichinh.service.llm.LlmJson;
import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ForecastRunJdbcRepository {
  private final JdbcTemplate db;

  public ForecastRunJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void lock(UUID security) {
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "forecast:" + security);
  }

  public List<Map<String, Object>> findCached(UUID securityId, String hash) {
    return db.queryForList(
        "SELECT id,llm_run_id,validation_round_id FROM llm_results WHERE source_domain='FINANCIAL' AND"
            + " security_id=? AND input_hash=? ORDER BY is_current DESC,created_at DESC LIMIT 1",
        securityId,
        hash);
  }

  public void expireRunning(UUID securityId) {
    db.update(
        "UPDATE llm_runs SET status='FAILED',finished_at=now(),error_message='RUN_LEASE_EXPIRED'"
            + " WHERE security_id=? AND news_article_id IS NULL AND status='RUNNING' AND"
            + " created_at<now()-interval '10 minutes'",
        securityId);
  }

  public List<UUID> findActiveRuns(UUID securityId) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE security_id=? AND news_article_id IS NULL AND status IN"
            + " ('RUNNING','PENDING_VALIDATION')",
        UUID.class,
        securityId);
  }

  public int countFailed(UUID securityId, String hash) {
    Integer count =
        db.queryForObject(
            "SELECT count(*) FROM llm_runs WHERE security_id=? AND input_hash=? AND status IN"
                + " ('FAILED','REJECTED')",
            Integer.class,
            securityId,
            hash);
    return count == null ? 0 : count;
  }

  public void insertRunning(
      UUID run,
      String model,
      String version,
      String hash,
      String metadataJson,
      UUID templateId,
      String task,
      UUID companyId,
      UUID securityId,
      String requestJson) {
    db.update(
        "INSERT INTO"
            + " llm_runs(id,operation_type,provider,model_name,prompt_version,status,input_hash,request_metadata,response_metadata,created_at,prompt_template_id,task_code,company_id,security_id,request_payload)"
            + " VALUES"
            + " (?,'FINANCIAL_EXPLAIN','GEMINI',?,?,'RUNNING',?,?::jsonb,'{}'::jsonb,now(),?,?,?,?,?::jsonb)",
        run,
        model,
        version,
        hash,
        metadataJson,
        templateId,
        task,
        companyId,
        securityId,
        requestJson);
  }

  public void appendAttempt(LlmJson json, UUID run, JsonNode request, LlmGateway.Attempt attempt) {
    LlmRunJdbcRepository.appendAttempt(db, json, run, request, attempt);
  }

  public List<JsonNode> readAttempts(LlmJson json, UUID run) {
    return LlmRunJdbcRepository.readAttempts(db, json, run);
  }

  public boolean stage(
      UUID run,
      String envelopeJson,
      String outputText,
      int httpStatus,
      Integer inputTokens,
      Integer outputTokens,
      int latency,
      String selectedModel) {
    return db.update(
            "UPDATE llm_runs SET"
                + " status='PENDING_VALIDATION',response_payload=?::jsonb,response_text=?,http_status=?,input_tokens=?,output_tokens=?,latency_ms=?,model_name=coalesce(?,model_name)"
                + " WHERE id=? AND status='RUNNING'",
            envelopeJson,
            outputText,
            httpStatus,
            inputTokens,
            outputTokens,
            latency,
            selectedModel,
            run)
        == 1;
  }

  public void fail(UUID run, String error) {
    db.update(
        "UPDATE llm_runs SET status='FAILED',finished_at=now(),error_message=? WHERE id=? AND"
            + " status='RUNNING'",
        error,
        run);
  }

  public List<Map<String, Object>> findForecastRunById(UUID run, String task) {
    return db.queryForList("SELECT * FROM llm_runs WHERE id=? AND task_code=?", run, task);
  }

  public List<Map<String, Object>> findForecastRunByIdForUpdate(UUID run) {
    return db.queryForList("SELECT * FROM llm_runs WHERE id=? FOR UPDATE", run);
  }

  public List<LlmPromptCatalog.Template> findTemplateById(UUID templateId, String task, LlmJson json) {
    return db.query(
        "SELECT * FROM llm_prompt_templates WHERE id=?",
        (r, n) ->
            new LlmPromptCatalog.Template(
                r.getObject("id", UUID.class),
                task,
                r.getString("version"),
                r.getString("system_prompt"),
                json.read(r.getString("request_schema")),
                json.read(r.getString("response_schema")),
                r.getString("checksum")),
        templateId);
  }

  public boolean countActiveTemplate(UUID templateId, String checksum) {
    Integer count =
        db.queryForObject(
            "SELECT count(*) FROM llm_prompt_templates WHERE id=? AND enabled AND checksum=?",
            Integer.class,
            templateId,
            checksum);
    return count != null && count == 1;
  }

  public List<UUID> findCurrentResultIds(UUID run) {
    return db.queryForList(
        "SELECT id FROM llm_results WHERE llm_run_id=? AND is_current", UUID.class, run);
  }

  public void updatePendingValidationRun(
      UUID run,
      String status,
      UUID round,
      String errorsJson,
      String errorMessage) {
    db.update(
        "UPDATE llm_runs SET"
            + " status=?,finished_at=now(),validation_round_id=?,validation_errors=?::jsonb,error_message=?"
            + " WHERE id=?",
        status,
        round,
        errorsJson,
        errorMessage,
        run);
  }

  public void updateValidationRound(UUID run, UUID round) {
    db.update("UPDATE llm_runs SET validation_round_id=? WHERE id=?", round, run);
  }

  public void demoteResultAndFailRun(UUID run, String status, String errorsJson, String errorMessage) {
    db.update("UPDATE llm_results SET is_current=false WHERE llm_run_id=?", run);
    db.update(
        "UPDATE llm_runs SET status=?,validation_errors=?::jsonb,error_message=? WHERE id=?",
        status,
        errorsJson,
        errorMessage,
        run);
  }

  public void updateResultOnSuccess(
      UUID run,
      UUID round,
      String policy,
      String hash,
      String qualityStatus) {
    db.update(
        "UPDATE llm_results SET validation_round_id=?,validation_policy_hash=?,input_hash=?,quality_status=? WHERE"
            + " llm_run_id=?",
        round,
        policy,
        hash,
        qualityStatus,
        run);
  }

  public int countSupersededResults(
      UUID run,
      UUID security,
      String task,
      LocalDate periodEnd,
      String targetScope) {
    Integer count =
        db.queryForObject(
            "SELECT count(*) FROM llm_results WHERE llm_run_id=? OR (security_id=? AND"
                + " task_code=? AND forecast_period_end=? AND target_scope=? AND is_current)",
            Integer.class,
            run,
            security,
            task,
            periodEnd,
            targetScope);
    return count == null ? 0 : count;
  }

  public int countNewerCutoffResults(
      UUID security,
      String task,
      LocalDate periodEnd,
      String targetScope,
      LocalDate asOfDate) {
    Integer count =
        db.queryForObject(
            "SELECT count(*) FROM llm_results WHERE source_domain='FINANCIAL' AND security_id=?"
                + " AND task_code=? AND forecast_period_end=? AND target_scope=? AND is_current AND as_of_date>?",
            Integer.class,
            security,
            task,
            periodEnd,
            targetScope,
            asOfDate);
    return count == null ? 0 : count;
  }

  public void retireCurrentResults(
      UUID security,
      String task,
      LocalDate periodEnd,
      String targetScope) {
    db.update(
        "UPDATE llm_results SET is_current=false WHERE source_domain='FINANCIAL' AND security_id=?"
            + " AND task_code=? AND forecast_period_end=? AND target_scope=? AND is_current",
        security,
        task,
        periodEnd,
        targetScope);
  }

  public void insertResult(
      UUID id,
      UUID run,
      UUID templateId,
      String task,
      UUID companyId,
      UUID securityId,
      LocalDate asOfDate,
      String sourceHash,
      String inputHash,
      String schemaVersion,
      String overview,
      String resultJson,
      String qualityStatus,
      UUID round,
      String policy,
      LocalDate periodStart,
      LocalDate periodEnd,
      String targetScope,
      boolean publish) {
    db.update(
        "INSERT INTO"
            + " llm_results(id,llm_run_id,prompt_template_id,task_code,source_domain,company_id,security_id,as_of_date,source_hash,input_hash,schema_version,overview,result_json,quality_status,validation_round_id,validation_policy_hash,forecast_period_start,forecast_period_end,target_scope,is_current)"
            + " VALUES (?,?,?,?,'FINANCIAL',?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)",
        id,
        run,
        templateId,
        task,
        companyId,
        securityId,
        asOfDate,
        sourceHash,
        inputHash,
        schemaVersion,
        overview,
        resultJson,
        qualityStatus,
        round,
        policy,
        periodStart,
        periodEnd,
        targetScope,
        publish);
  }

  public void updateRunSuccess(UUID run) {
    db.update(
        "UPDATE llm_runs SET status='SUCCESS',validation_errors='[]'::jsonb,error_message=null"
            + " WHERE id=?",
        run);
  }

  public List<String> findValidationResultsJson(UUID runId) {
    return db.query(
        "SELECT row_to_json(v)::text FROM validation_results v WHERE llm_run_id=? ORDER BY"
            + " checked_at,rule_code",
        (rs, n) -> rs.getString(1),
        runId);
  }

  public List<Map<String, Object>> findResults(
      UUID security,
      String policy,
      LocalDate periodEnd,
      int limit) {
    return db.queryForList(
        "SELECT r.* FROM llm_results r JOIN llm_prompt_templates t ON t.id=r.prompt_template_id"
            + " AND t.enabled WHERE r.source_domain='FINANCIAL' AND r.security_id=? AND"
            + " r.is_current AND r.validation_policy_hash=? AND r.validation_round_id IS NOT"
            + " NULL AND (?::date IS NULL OR r.forecast_period_end=?::date) ORDER BY r.as_of_date DESC,r.created_at DESC LIMIT ?",
        security,
        policy,
        periodEnd,
        periodEnd,
        Math.max(1, Math.min(50, limit)));
  }

  public String findRequestMetadata(UUID run) {
    return db.queryForObject(
        "SELECT request_metadata::text FROM llm_runs WHERE id=?", String.class, run);
  }

  public Optional<String> findRetryRequest(UUID run, String task) {
    return db.query("""
        SELECT (request_metadata->'forecast_request')::text FROM llm_runs
        WHERE id=? AND task_code=? AND status IN ('FAILED','REJECTED')
          AND jsonb_typeof(request_metadata->'forecast_request')='object'
        """, (rs, row) -> rs.getString(1), run, task).stream().findFirst();
  }

  public List<UUID> findPendingRuns(String task, int limit) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE task_code=? AND status='PENDING_VALIDATION' ORDER BY"
            + " created_at LIMIT ?",
        UUID.class,
        task,
        Math.max(1, Math.min(50, limit)));
  }

  public List<UUID> findRunsBySecurity(UUID security, String task, int limit) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE security_id=? AND task_code=? ORDER BY created_at DESC LIMIT"
            + " ?",
        UUID.class,
        security,
        task,
        Math.max(1, Math.min(50, limit)));
  }
}
