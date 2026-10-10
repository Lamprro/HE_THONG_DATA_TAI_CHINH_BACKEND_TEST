package com.hethongdata.taichinh.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.SourcePoint;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import java.math.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ForecastValidationService {
  public record Evaluation(UUID round, List<String> errors, boolean technical) {}

  private static final Set<String> REQUIRED =
      Set.of(
          "FORECAST_SCHEMA",
          "FORECAST_SOURCE",
          "FORECAST_EVIDENCE",
          "FORECAST_SCENARIOS",
          "FORECAST_COVERAGE",
          "FORECAST_SNAPSHOT",
          "FORECAST_PROMPT");
  private final JdbcTemplate db;
  private final LlmJson json;
  private final ChecksumService hashes;

  public ForecastValidationService(JdbcTemplate db, LlmJson json, ChecksumService hashes) {
    this.db = db;
    this.json = json;
    this.hashes = hashes;
  }

  private List<JsonNode> rules() {
    var rules =
        db.query(
            "SELECT"
                + " jsonb_build_object('id',id,'code',code,'executor',executor_key,'severity',severity,'config',rule_config)::text"
                + " FROM validation_rules WHERE data_domain='LLM_FORECAST' AND is_active ORDER BY"
                + " code",
            (r, n) -> json.read(r.getString(1)));
    var codes = new HashSet<String>();
    for (var r : rules) {
      var tasks = r.path("config").path("tasks");
      boolean applies = false;
      for (var t : tasks) if (ForecastPromptService.TASK.equals(t.asText())) applies = true;
      if (!REQUIRED.contains(r.path("code").asText())
          || !r.path("code").equals(r.path("executor"))
          || !Set.of("ERROR", "CRITICAL").contains(r.path("severity").asText())
          || !r.path("config").hasNonNull("version")
          || !tasks.isArray()
          || !applies)
        throw new IllegalStateException(
            "Invalid forecast blocking rule: " + r.path("code").asText());
      codes.add(r.path("code").asText());
    }
    if (!codes.equals(REQUIRED))
      throw new IllegalStateException("Missing mandatory forecast rules");
    return rules;
  }

  public String fingerprint() {
    return hashes.sha256(json.canonical(json.mapper().valueToTree(rules())).toString());
  }

  public Evaluation evaluate(
      UUID run,
      LlmPromptCatalog.Template template,
      ForecastContextService.Context ctx,
      JsonNode output,
      List<String> readiness,
      boolean sourceCurrent,
      boolean promptCurrent,
      String expectedPolicy) {
    UUID round = UUID.randomUUID();
    List<JsonNode> rules;
    try {
      rules = rules();
    } catch (IllegalStateException e) {
      return new Evaluation(round, List.of(e.getMessage()), true);
    }
    var errors = new ArrayList<String>();
    boolean valid =
        output != null
            && readiness.isEmpty()
            && LlmSchemaValidator.validate(template.responseSchema(), output).isEmpty();
    var points = new HashMap<String, SourcePoint>();
    for (var p : ctx.points()) points.put(p.id(), p);
    for (var rule : rules) {
      var issues = new ArrayList<String>();
      String code = rule.path("code").asText();
      boolean skip = false;
      switch (code) {
        case "FORECAST_SCHEMA" -> {
          issues.addAll(readiness);
          if (output == null) issues.add("OUTPUT_MISSING");
          else issues.addAll(LlmSchemaValidator.validate(template.responseSchema(), output));
        }
        case "FORECAST_SNAPSHOT" -> {
          if (!sourceCurrent) issues.add("FORECAST_SOURCE_CHANGED");
        }
        case "FORECAST_PROMPT" -> {
          if (!promptCurrent) issues.add("FORECAST_TEMPLATE_CHANGED");
          if (!fingerprint().equals(expectedPolicy)) issues.add("FORECAST_RULES_CHANGED");
        }
        default -> {
          if (!valid) {
            skip = true;
            break;
          }
          switch (code) {
            case "FORECAST_SOURCE" -> {
              if (!ctx.company().toString().equals(output.path("company_id").asText())
                  || !ctx.request()
                      .securityId()
                      .toString()
                      .equals(output.path("security_id").asText())
                  || !ctx.request().asOfDate().toString().equals(output.path("as_of_date").asText())
                  || !ctx.targetDate()
                      .toString()
                      .equals(output.path("forecast_period_end").asText()))
                issues.add("FORECAST_SOURCE_ID_OR_DATE_MISMATCH");
            }
            case "FORECAST_COVERAGE" -> {
              if (!ctx.eligible()) issues.addAll(ctx.issues());
              if ("INSUFFICIENT".equals(output.path("status").asText()))
                issues.add("FORECAST_INSUFFICIENT");
              if (!ctx.coverage().limitations().isEmpty()
                  && "SUFFICIENT".equals(output.path("status").asText()))
                issues.add("FORECAST_LIMITATIONS_NOT_ACKNOWLEDGED");
              if (ctx.coverage().macroObservations() == 0 && output.path("macro_used").asBoolean())
                issues.add("MACRO_NOT_IN_SOURCE");
              boolean macroEvidence = false;
              for (var f : output.path("forecasts"))
                for (String name : List.of("bear", "base", "bull"))
                  for (var id : f.path(name).path("evidence_ids"))
                    if (points.containsKey(id.asText())
                        && "MACRO".equals(points.get(id.asText()).domain())) macroEvidence = true;
              if (macroEvidence != output.path("macro_used").asBoolean())
                issues.add("MACRO_FLAG_EVIDENCE_MISMATCH");
            }
            case "FORECAST_EVIDENCE", "FORECAST_SCENARIOS" -> {
              var targets = new HashSet<String>();
              for (var f : output.path("forecasts")) {
                String target = f.path("metric_code").asText();
                var targetType =
                    com.hethongdata.taichinh.dto.forecast.ForecastRequest.Target.valueOf(target);
                if (!targets.add(target)) issues.add("DUPLICATE_FORECAST_TARGET");
                var latest =
                    ctx.points().stream()
                        .filter(p -> targetType.matches(p.domain(), p.code()))
                        .max(Comparator.comparing(SourcePoint::periodEnd))
                        .orElse(null);
                if (latest == null || !latest.id().equals(f.path("base_point_id").asText())) {
                  issues.add("FORECAST_BASE_NOT_LATEST_TARGET");
                  continue;
                }
                if (latest.value().signum() <= 0)
                  issues.add("NON_POSITIVE_BASE_REQUIRES_DIFFERENT_MODEL");
                BigDecimal previous = null;
                for (String scenario : List.of("bear", "base", "bull")) {
                  var row = f.path(scenario);
                  var ids = new HashSet<String>();
                  for (var ref : row.path("evidence_ids")) ids.add(ref.asText());
                  if (!ids.contains(latest.id())
                      || ids.stream().anyMatch(id -> !points.containsKey(id)))
                    issues.add("FORECAST_EVIDENCE_NOT_IN_SOURCE");
                  if (ids.stream()
                      .noneMatch(
                          id ->
                              points.containsKey(id)
                                  && targetType.matches(
                                      points.get(id).domain(), points.get(id).code())
                                  && points.get(id).periodEnd().isBefore(latest.periodEnd())))
                    issues.add("FORECAST_HISTORY_EVIDENCE_REQUIRED");
                  BigDecimal growth = row.path("growth_percent").decimalValue();
                  if (previous != null && growth.compareTo(previous) < 0)
                    issues.add("FORECAST_SCENARIOS_NOT_ORDERED");
                  previous = growth;
                  if (targetType
                          == com.hethongdata.taichinh.dto.forecast.ForecastRequest.Target
                              .STOCK_PRICE
                      && (growth.compareTo(BigDecimal.valueOf(-100)) <= 0
                          || !"VND_PER_SHARE".equals(latest.unit())))
                    issues.add("INVALID_PRICE_SCENARIO_OR_UNIT");
                }
              }
              if (!targets.equals(
                  ctx.request().targets().stream()
                      .map(Enum::name)
                      .collect(java.util.stream.Collectors.toSet())))
                issues.add("FORECAST_TARGETS_MISMATCH");
            }
          }
        }
      }
      String state = skip ? "SKIP" : issues.isEmpty() ? "PASS" : "FAIL";
      errors.addAll(issues);
      db.update(
          "INSERT INTO"
              + " validation_results(id,validation_rule_id,rule_code,severity,result_status,handling_status,checked_at,validation_target,llm_run_id,validation_round_id,rule_snapshot,observed_value,expected_value,message)"
              + " VALUES (?,?,?,?,?,?,now(),'LLM_OUTPUT',?,?,?::jsonb,?,'PASS',?)",
          UUID.randomUUID(),
          rule.path("id").asLong(),
          code,
          rule.path("severity").asText(),
          state,
          issues.isEmpty() ? "NOT_REQUIRED" : "OPEN",
          run,
          round,
          rule.toString(),
          json.mapper().valueToTree(issues).toString(),
          skip
              ? "Prerequisite schema/response failed"
              : issues.isEmpty() ? "Rule passed" : String.join("; ", issues));
    }
    return new Evaluation(round, errors.stream().distinct().toList(), false);
  }

  public JsonNode projections(ForecastContextService.Context ctx, JsonNode output) {
    var result = output.deepCopy();
    var values =
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).putArray("calculated_values");
    for (var forecast : output.path("forecasts")) {
      var point =
          ctx.points().stream()
              .filter(p -> p.id().equals(forecast.path("base_point_id").asText()))
              .findFirst()
              .orElseThrow();
      var row =
          values
              .addObject()
              .put("metric_code", forecast.path("metric_code").asText())
              .put("unit", point.unit())
              .put("base_value", point.value())
              .put("base_point_id", point.id())
              .put("formula", "base_value * (1 + growth_percent / 100)")
              .put("calculation_version", "scenario-v1");
      row.put("source_period_end", point.periodEnd().toString())
          .put("forecast_period_end", ctx.targetDate().toString())
          .put(
              "forecast_basis",
              "STOCK_PRICE".equals(forecast.path("metric_code").asText())
                  ? "UNCALIBRATED_NOMINAL_CLOSE_PRICE_SCENARIO_NOT_TOTAL_RETURN_OR_FAIR_VALUE"
                  : "UNCALIBRATED_SCENARIO_RELATIVE_TO_REPORTED_VALUE_NOT_TTM");
      for (String scenario : List.of("bear", "base", "bull"))
        row.put(
            scenario,
            point
                .value()
                .multiply(
                    BigDecimal.ONE.add(
                        forecast
                            .path(scenario)
                            .path("growth_percent")
                            .decimalValue()
                            .movePointLeft(2)))
                .setScale(8, RoundingMode.HALF_UP));
    }
    return result;
  }
}
