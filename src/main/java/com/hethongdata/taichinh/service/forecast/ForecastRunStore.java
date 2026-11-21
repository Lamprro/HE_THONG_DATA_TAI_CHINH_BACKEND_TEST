package com.hethongdata.taichinh.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.forecast.*;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import com.hethongdata.taichinh.repository.jdbc.forecast.ForecastRunJdbcRepository;
import com.hethongdata.taichinh.service.llm.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastRunStore {
  private final ForecastRunJdbcRepository repo;
  private final LlmJson json;
  private final ForecastContextService contexts;
  private final ForecastValidationService validations;
  private final com.hethongdata.taichinh.service.ingestion.ChecksumService hashes;

  public ForecastRunStore(
      ForecastRunJdbcRepository repo,
      LlmJson json,
      ForecastContextService contexts,
      ForecastValidationService validations,
      com.hethongdata.taichinh.service.ingestion.ChecksumService hashes) {
    this.repo = repo;
    this.json = json;
    this.contexts = contexts;
    this.validations = validations;
    this.hashes = hashes;
  }

  private void lock(UUID security) {
    repo.lock(security);
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
    var cached = repo.findCached(ctx.request().securityId(), hash);
    if (!cached.isEmpty()) {
      var previous = cached.getFirst();
      if (previous.get("validation_round_id") == null
          || !validations.publicationRoundValid(
              (UUID) previous.get("llm_run_id"), (UUID) previous.get("validation_round_id")))
        return new Outcome(
            "REVALIDATION_REQUIRED",
            (UUID) previous.get("llm_run_id"),
            null,
            List.of("Cached result lacks a complete passing blocking-validation round"));
      return new Outcome(
          "CACHED",
          (UUID) cached.getFirst().get("llm_run_id"),
          (UUID) cached.getFirst().get("id"),
          List.of());
    }
    repo.expireRunning(ctx.request().securityId());
    var active = repo.findActiveRuns(ctx.request().securityId());
    if (!active.isEmpty()) return new Outcome("RUNNING", active.getFirst(), null, List.of());
    if (repo.countFailed(ctx.request().securityId(), hash) >= 3)
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
    repo.insertRunning(
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
    repo.appendAttempt(json, run, request, a);
  }

  @Transactional
  public boolean stage(UUID run, LlmGateway.Reply reply, int latency) {
    JsonNode envelope;
    try {
      envelope = json.read(reply.rawBody());
    } catch (Exception e) {
      envelope = json.mapper().createObjectNode().put("raw_body", reply.rawBody());
    }
    return repo.stage(
        run,
        envelope.toString(),
        reply.outputText(),
        reply.httpStatus(),
        reply.inputTokens(),
        reply.outputTokens(),
        latency,
        reply.selectedModel());
  }

  @Transactional
  public Outcome fail(UUID run, String error) {
    repo.fail(run, error);
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
    var rows = repo.findForecastRunById(run, ForecastPromptService.TASK);
    if (rows.isEmpty()) throw new IllegalArgumentException("Forecast run not found");
    UUID security = (UUID) rows.getFirst().get("security_id");
    lock(security);
    var row = repo.findForecastRunByIdForUpdate(run).getFirst();
    if ("RUNNING".equals(row.get("status"))) return new Outcome("RUNNING", run, null, List.of());
    boolean pending = "PENDING_VALIDATION".equals(row.get("status"));
    var metadata = json.read(row.get("request_metadata").toString());
    var request =
        json.mapper().convertValue(metadata.path("forecast_request"), ForecastRequest.class);
    var ctx = restore(metadata, request);
    var now = contexts.build(request);
    var template = repo.findTemplateById((UUID) row.get("prompt_template_id"), ForecastPromptService.TASK, json).getFirst();
    boolean promptActive = repo.countActiveTemplate(template.id(), template.checksum());
    String originalChecksum = metadata.path("template_checksum").asText();
    if (!originalChecksum.isEmpty()) promptActive &= originalChecksum.equals(template.checksum());
    else {
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
    var current = repo.findCurrentResultIds(run);
    if (pending)
      repo.updatePendingValidationRun(
          run,
          status,
          evaluation.round(),
          json.mapper().valueToTree(evaluation.errors()).toString(),
          String.join("; ", evaluation.errors()));
    else repo.updateValidationRound(run, evaluation.round());
    if (!"SUCCESS".equals(status)) {
      if (!current.isEmpty()) {
        repo.demoteResultAndFailRun(
            run,
            status,
            json.mapper().valueToTree(evaluation.errors()).toString(),
            String.join("; ", evaluation.errors()));
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
      repo.updateResultOnSuccess(run, evaluation.round(), policy, hash, evaluation.qualityStatus());
      return new Outcome("SUCCESS", run, current.getFirst(), List.of());
    }
    LocalDate periodEnd = ctx.targetDate();
    LocalDate periodStart = ForecastContextService.periodStart(periodEnd);
    String targetScope = request.targetScope();
    if (!pending
        && repo.countSupersededResults(run, security, ForecastPromptService.TASK, ctx.targetDate(), request.targetScope()) > 0)
      return new Outcome(
          "SUCCESS", run, null, List.of("Audit only: never reactivate a superseded result"));
    var result = validations.projections(ctx, output);
    UUID id = UUID.randomUUID();
    boolean publish = repo.countNewerCutoffResults(
        security, ForecastPromptService.TASK, ctx.targetDate(), request.targetScope(), request.asOfDate()) == 0;
    if (publish)
      repo.retireCurrentResults(security, ForecastPromptService.TASK, ctx.targetDate(), request.targetScope());
    repo.insertResult(
        id,
        run,
        template.id(),
        ForecastPromptService.TASK,
        ctx.company(),
        security,
        request.asOfDate(),
        ctx.hash(),
        (String) row.get("input_hash"),
        output.path("schema_version").asText(),
        output.path("overview").asText(),
        result.toString(),
        evaluation.qualityStatus(),
        evaluation.round(),
        policy,
        periodStart,
        periodEnd,
        targetScope,
        publish);
    repo.updateRunSuccess(run);
    return new Outcome("SUCCESS", run, id, List.of());
  }

  public Run run(UUID id) {
    var rows = repo.findForecastRunById(id, ForecastPromptService.TASK);
    if (rows.isEmpty()) throw new IllegalArgumentException("Forecast run not found");
    var r = rows.getFirst();
    var attempts = repo.readAttempts(json, id);
    var audit = new ArrayList<JsonNode>();
    for (String a : repo.findValidationResultsJson(id)) audit.add(json.read(a));
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
        errors,
        (UUID) r.get("validation_round_id"));
  }

  public List<Result> results(UUID security, int limit) {
    return results(security, limit, null);
  }

  public List<Result> results(UUID security, int limit, LocalDate periodEnd) {
    String policy;
    try {
      policy = validations.fingerprint();
    } catch (IllegalStateException e) {
      return List.of();
    }
    var output = new ArrayList<Result>();
    var rows = repo.findResults(security, policy, periodEnd, limit);
    for (var r : rows) {
      UUID run = (UUID) r.get("llm_run_id");
      var metadata = repo.findRequestMetadata(run);
      var request =
          json.mapper()
              .convertValue(json.read(metadata).path("forecast_request"), ForecastRequest.class);
      var current = contexts.build(request);
      if (!current.eligible() || !current.hash().equals(r.get("source_hash"))) continue;
      if (!validations.publicationRoundValid(run, (UUID) r.get("validation_round_id"))) continue;
      output.add(
          new Result(
              (UUID) r.get("id"),
              run,
              (UUID) r.get("company_id"),
              security,
              ((java.sql.Date) r.get("as_of_date")).toLocalDate(),
              ((java.sql.Date) r.get("forecast_period_start")).toLocalDate(),
              ((java.sql.Date) r.get("forecast_period_end")).toLocalDate(),
              r.get("target_scope").toString(),
              r.get("quality_status").toString(),
              json.read(r.get("result_json").toString())));
    }
    return List.copyOf(output);
  }

  public List<UUID> pending(int limit) {
    return repo.findPendingRuns(ForecastPromptService.TASK, limit);
  }

  public List<UUID> runs(UUID security, int limit) {
    return repo.findRunsBySecurity(security, ForecastPromptService.TASK, limit);
  }
}
