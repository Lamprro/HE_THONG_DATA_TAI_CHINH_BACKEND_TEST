package com.hethongdata.taichinh.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.forecast.*;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import com.hethongdata.taichinh.service.llm.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastRunStore {
  private final JdbcTemplate db;
  private final LlmJson json;
  private final ForecastContextService contexts;
  private final ForecastValidationService validations;
  private final com.hethongdata.taichinh.service.ingestion.ChecksumService hashes;

  public ForecastRunStore(
      JdbcTemplate db,
      LlmJson json,
      ForecastContextService contexts,
      ForecastValidationService validations,
      com.hethongdata.taichinh.service.ingestion.ChecksumService hashes) {
    this.db = db;
    this.json = json;
    this.contexts = contexts;
    this.validations = validations;
    this.hashes = hashes;
  }

  private void lock(UUID security) {
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "forecast:" + security);
  }

  @Transactional
  public Outcome claim(
      ForecastContextService.Context ctx,
      LlmPromptCatalog.Template template,
      String hash,
      JsonNode request,
      String model,
      String policy,
      String routingKey) {
    lock(ctx.request().securityId());
    var cached =
        db.queryForList(
            "SELECT id,llm_run_id FROM llm_results WHERE source_domain='FINANCIAL' AND"
                + " security_id=? AND input_hash=? AND is_current",
            ctx.request().securityId(),
            hash);
    if (!cached.isEmpty())
      return new Outcome(
          "CACHED",
          (UUID) cached.getFirst().get("llm_run_id"),
          (UUID) cached.getFirst().get("id"),
          List.of());
    db.update(
        "UPDATE llm_runs SET status='FAILED',finished_at=now(),error_message='RUN_LEASE_EXPIRED'"
            + " WHERE security_id=? AND news_article_id IS NULL AND status='RUNNING' AND"
            + " created_at<now()-interval '10 minutes'",
        ctx.request().securityId());
    var active =
        db.queryForList(
            "SELECT id FROM llm_runs WHERE security_id=? AND news_article_id IS NULL AND status IN"
                + " ('RUNNING','PENDING_VALIDATION')",
            UUID.class,
            ctx.request().securityId());
    if (!active.isEmpty()) return new Outcome("RUNNING", active.getFirst(), null, List.of());
    if (db.queryForObject(
            "SELECT count(*) FROM llm_runs WHERE security_id=? AND input_hash=? AND status IN"
                + " ('FAILED','REJECTED')",
            Integer.class,
            ctx.request().securityId(),
            hash)
        >= 3)
      return new Outcome(
          "RETRY_LIMIT", null, null, List.of("Three failures for unchanged forecast input"));
    UUID run = UUID.randomUUID();
    var metadata = json.mapper().createObjectNode();
    metadata.set("forecast_request", json.mapper().valueToTree(ctx.request()));
    metadata.set("input", ctx.input());
    metadata.set("coverage", json.mapper().valueToTree(ctx.coverage()));
    metadata
        .put("source_hash", ctx.hash())
        .put("validation_policy", policy)
        .put("template_checksum", template.checksum())
        .put("routing_key", routingKey);
    db.update(
        "INSERT INTO"
            + " llm_runs(id,operation_type,provider,model_name,prompt_version,status,input_hash,request_metadata,response_metadata,created_at,prompt_template_id,task_code,company_id,security_id,request_payload)"
            + " VALUES"
            + " (?,'FINANCIAL_EXPLAIN','GEMINI',?,?,'RUNNING',?,?::jsonb,'{}'::jsonb,now(),?,?,?,?,?::jsonb)",
        run,
        model,
        template.version(),
        hash,
        metadata.toString(),
        template.id(),
        ForecastPromptService.TASK,
        ctx.company(),
        ctx.request().securityId(),
        request.toString());
    return new Outcome("CLAIMED", run, null, List.of());
  }

  @Transactional
  public void attempt(UUID run, JsonNode request, LlmGateway.Attempt a) {
    com.hethongdata.taichinh.service.llm.LlmAttemptAudit.append(db, json, run, request, a);
  }

  @Transactional
  public boolean stage(UUID run, LlmGateway.Reply reply, int latency) {
    JsonNode envelope;
    try {
      envelope = json.read(reply.rawBody());
    } catch (Exception e) {
      envelope = json.mapper().createObjectNode().put("raw_body", reply.rawBody());
    }
    return db.update(
            "UPDATE llm_runs SET"
                + " status='PENDING_VALIDATION',response_payload=?::jsonb,response_text=?,http_status=?,input_tokens=?,output_tokens=?,latency_ms=?,model_name=coalesce(?,model_name)"
                + " WHERE id=? AND status='RUNNING'",
            envelope.toString(),
            reply.outputText(),
            reply.httpStatus(),
            reply.inputTokens(),
            reply.outputTokens(),
            latency,
            reply.selectedModel(),
            run)
        == 1;
  }

  @Transactional
  public Outcome fail(UUID run, String error) {
    db.update(
        "UPDATE llm_runs SET status='FAILED',finished_at=now(),error_message=? WHERE id=? AND"
            + " status='RUNNING'",
        error,
        run);
    return new Outcome("FAILED", run, null, List.of(error));
  }

  private ForecastContextService.Context restore(JsonNode metadata, ForecastRequest request) {
    JsonNode input = metadata.path("input");
    var points = new ArrayList<SourcePoint>();
    for (var p : input.path("points")) points.add(json.mapper().convertValue(p, SourcePoint.class));
    var metrics = new ArrayList<DerivedMetric>();
    for (var m : input.path("derived_metrics"))
      metrics.add(json.mapper().convertValue(m, DerivedMetric.class));
    return new ForecastContextService.Context(
        UUID.fromString(input.path("company_id").asText()),
        request,
        LocalDate.parse(input.path("forecast_period_end").asText()),
        List.copyOf(points),
        List.copyOf(metrics),
        json.mapper().convertValue(metadata.path("coverage"), Coverage.class),
        List.of(),
        input,
        metadata.path("source_hash").asText());
  }

  @Transactional
  public Outcome validate(UUID run) {
    var rows =
        db.queryForList(
            "SELECT * FROM llm_runs WHERE id=? AND task_code=?", run, ForecastPromptService.TASK);
    if (rows.isEmpty()) throw new IllegalArgumentException("Forecast run not found");
    UUID security = (UUID) rows.getFirst().get("security_id");
    lock(security);
    var row = db.queryForList("SELECT * FROM llm_runs WHERE id=? FOR UPDATE", run).getFirst();
    if ("RUNNING".equals(row.get("status"))) return new Outcome("RUNNING", run, null, List.of());
    boolean pending = "PENDING_VALIDATION".equals(row.get("status"));
    var metadata = json.read(row.get("request_metadata").toString());
    var request =
        json.mapper().convertValue(metadata.path("forecast_request"), ForecastRequest.class);
    var ctx = restore(metadata, request);
    var now = contexts.build(request);
    var template =
        db.query(
                "SELECT * FROM llm_prompt_templates WHERE id=?",
                (r, n) ->
                    new LlmPromptCatalog.Template(
                        r.getObject("id", UUID.class),
                        ForecastPromptService.TASK,
                        r.getString("version"),
                        r.getString("system_prompt"),
                        json.read(r.getString("request_schema")),
                        json.read(r.getString("response_schema")),
                        r.getString("checksum")),
                row.get("prompt_template_id"))
            .getFirst();
    boolean promptActive =
        db.queryForObject(
                "SELECT count(*) FROM llm_prompt_templates WHERE id=? AND enabled AND checksum=?",
                Integer.class,
                template.id(),
                template.checksum())
            == 1;
    String originalChecksum = metadata.path("template_checksum").asText();
    if (!originalChecksum.isEmpty()) promptActive &= originalChecksum.equals(template.checksum());
    else {
      // Legacy run: verify its original input identity rather than silently assuming the current
      // prompt.
      String originalHash =
          hashes.sha256(
              template.checksum()
                  + "\n"
                  + metadata.path("routing_key").asText()
                  + "\n"
                  + metadata.path("validation_policy").asText()
                  + "\n"
                  + json.canonical(json.read(row.get("request_payload").toString())));
      promptActive &= originalHash.equals(row.get("input_hash"));
    }
    var readiness = new ArrayList<String>();
    int http = row.get("http_status") == null ? 0 : ((Number) row.get("http_status")).intValue();
    JsonNode output = null;
    if (http < 200 || http >= 300) readiness.add("PROVIDER_HTTP_" + http);
    else if (row.get("response_text") == null) readiness.add("PROVIDER_BLOCKED_OR_INCOMPLETE");
    else
      try {
        output = json.read(row.get("response_text").toString());
      } catch (Exception e) {
        readiness.add("RESPONSE_NOT_JSON");
      }
    String policy;
    try {
      policy = pending ? metadata.path("validation_policy").asText() : validations.fingerprint();
    } catch (IllegalStateException e) {
      return new Outcome("VALIDATION_CONFIGURATION_ERROR", run, null, List.of(e.getMessage()));
    }
    var evaluation =
        validations.evaluate(
            run,
            template,
            ctx,
            output,
            readiness,
            now.eligible() && now.hash().equals(ctx.hash()),
            promptActive,
            policy);
    String status =
        http < 200 || http >= 300 || evaluation.technical()
            ? "FAILED"
            : evaluation.errors().isEmpty() ? "SUCCESS" : "REJECTED";
    var current =
        db.queryForList(
            "SELECT id FROM llm_results WHERE llm_run_id=? AND is_current", UUID.class, run);
    if (pending)
      db.update(
          "UPDATE llm_runs SET"
              + " status=?,finished_at=now(),validation_round_id=?,validation_errors=?::jsonb,error_message=?"
              + " WHERE id=?",
          status,
          evaluation.round(),
          json.mapper().valueToTree(evaluation.errors()).toString(),
          String.join("; ", evaluation.errors()),
          run);
    else db.update("UPDATE llm_runs SET validation_round_id=? WHERE id=?", evaluation.round(), run);
    if (!"SUCCESS".equals(status)) {
      if (!current.isEmpty()) {
        db.update("UPDATE llm_results SET is_current=false WHERE llm_run_id=?", run);
        db.update(
            "UPDATE llm_runs SET status=?,validation_errors=?::jsonb,error_message=? WHERE id=?",
            status,
            json.mapper().valueToTree(evaluation.errors()).toString(),
            String.join("; ", evaluation.errors()),
            run);
      }
      return new Outcome(status, run, null, evaluation.errors());
    }
    if (!current.isEmpty()) {
      String hash =
          hashes.sha256(
              template.checksum()
                  + "\n"
                  + metadata.path("routing_key").asText()
                  + "\n"
                  + policy
                  + "\n"
                  + json.canonical(json.read(row.get("request_payload").toString())));
      db.update(
          "UPDATE llm_results SET validation_round_id=?,validation_policy_hash=?,input_hash=? WHERE"
              + " llm_run_id=?",
          evaluation.round(),
          policy,
          hash,
          run);
      return new Outcome("SUCCESS", run, current.getFirst(), List.of());
    }
    if (!pending
        && db.queryForObject(
                "SELECT count(*) FROM llm_results WHERE llm_run_id=? OR (security_id=? AND"
                    + " as_of_date=? AND is_current)",
                Integer.class,
                run,
                security,
                request.asOfDate())
            > 0)
      return new Outcome(
          "SUCCESS", run, null, List.of("Audit only: never reactivate a superseded result"));
    var result = validations.projections(ctx, output);
    UUID id = UUID.randomUUID();
    db.update(
        "UPDATE llm_results SET is_current=false WHERE source_domain='FINANCIAL' AND security_id=?"
            + " AND as_of_date=? AND is_current",
        security,
        request.asOfDate());
    db.update(
        "INSERT INTO"
            + " llm_results(id,llm_run_id,prompt_template_id,task_code,source_domain,company_id,security_id,as_of_date,source_hash,input_hash,schema_version,overview,result_json,quality_status,validation_round_id,validation_policy_hash)"
            + " VALUES (?,?,?,?,'FINANCIAL',?,?,?,?,?,?,?,?::jsonb,'WARNING',?,?)",
        id,
        run,
        template.id(),
        ForecastPromptService.TASK,
        ctx.company(),
        security,
        request.asOfDate(),
        ctx.hash(),
        row.get("input_hash"),
        output.path("schema_version").asText(),
        output.path("overview").asText(),
        result.toString(),
        evaluation.round(),
        policy);
    db.update(
        "UPDATE llm_runs SET status='SUCCESS',validation_errors='[]'::jsonb,error_message=null"
            + " WHERE id=?",
        run);
    return new Outcome("SUCCESS", run, id, List.of());
  }

  public Run run(UUID id) {
    var rows =
        db.queryForList(
            "SELECT * FROM llm_runs WHERE id=? AND task_code=?", id, ForecastPromptService.TASK);
    if (rows.isEmpty()) throw new IllegalArgumentException("Forecast run not found");
    var r = rows.getFirst();
    var attempts = com.hethongdata.taichinh.service.llm.LlmAttemptAudit.read(db, json, id);
    var audit =
        db.query(
            "SELECT row_to_json(v)::text FROM validation_results v WHERE llm_run_id=? ORDER BY"
                + " checked_at,rule_code",
            (rs, n) -> json.read(rs.getString(1)),
            id);
    var errors = new ArrayList<String>();
    for (var e : json.read(r.get("validation_errors").toString())) errors.add(e.asText());
    return new Run(
        id,
        r.get("status").toString(),
        ForecastPromptService.TASK,
        (UUID) r.get("company_id"),
        (UUID) r.get("security_id"),
        Objects.toString(r.get("provider"), null),
        Objects.toString(r.get("model_name"), null),
        (Integer) r.get("http_status"),
        (Integer) r.get("input_tokens"),
        (Integer) r.get("output_tokens"),
        (Integer) r.get("latency_ms"),
        Objects.toString(r.get("error_message"), null),
        ((java.sql.Timestamp) r.get("created_at")).toInstant(),
        json.read(r.get("request_payload").toString()),
        r.get("response_payload") == null ? null : json.read(r.get("response_payload").toString()),
        attempts,
        audit,
        errors);
  }

  public List<Result> results(UUID security, int limit) {
    String policy;
    try {
      policy = validations.fingerprint();
    } catch (IllegalStateException e) {
      return List.of();
    }
    var output = new ArrayList<Result>();
    var rows =
        db.queryForList(
            "SELECT r.* FROM llm_results r JOIN llm_prompt_templates t ON t.id=r.prompt_template_id"
                + " AND t.enabled WHERE r.source_domain='FINANCIAL' AND r.security_id=? AND"
                + " r.is_current AND r.validation_policy_hash=? AND r.validation_round_id IS NOT"
                + " NULL ORDER BY r.created_at DESC LIMIT ?",
            security,
            policy,
            Math.max(1, Math.min(50, limit)));
    for (var r : rows) {
      UUID run = (UUID) r.get("llm_run_id");
      var metadata =
          db.queryForObject(
              "SELECT request_metadata::text FROM llm_runs WHERE id=?", String.class, run);
      var request =
          json.mapper()
              .convertValue(json.read(metadata).path("forecast_request"), ForecastRequest.class);
      var current = contexts.build(request);
      if (!current.eligible() || !current.hash().equals(r.get("source_hash"))) continue;
      if (db.queryForObject(
              "SELECT count(*) FROM validation_results WHERE llm_run_id=? AND validation_round_id=?"
                  + " AND result_status='PASS'",
              Integer.class,
              run,
              r.get("validation_round_id"))
          != 7) continue;
      output.add(
          new Result(
              (UUID) r.get("id"),
              run,
              (UUID) r.get("company_id"),
              security,
              ((java.sql.Date) r.get("as_of_date")).toLocalDate(),
              r.get("quality_status").toString(),
              json.read(r.get("result_json").toString())));
    }
    return List.copyOf(output);
  }

  public List<UUID> pending(int limit) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE task_code=? AND status='PENDING_VALIDATION' ORDER BY"
            + " created_at LIMIT ?",
        UUID.class,
        ForecastPromptService.TASK,
        Math.max(1, Math.min(50, limit)));
  }

  public List<UUID> runs(UUID security, int limit) {
    return db.queryForList(
        "SELECT id FROM llm_runs WHERE security_id=? AND task_code=? ORDER BY created_at DESC LIMIT"
            + " ?",
        UUID.class,
        security,
        ForecastPromptService.TASK,
        Math.max(1, Math.min(50, limit)));
  }
}
