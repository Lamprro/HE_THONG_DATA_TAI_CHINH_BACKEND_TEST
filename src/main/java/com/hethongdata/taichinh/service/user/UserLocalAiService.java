package com.hethongdata.taichinh.service.user;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.repository.jdbc.user.UserLocalAiQueryRepository;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/**
 * Reads the registered suite's progress, never its private inputs, artifacts or raw predictions.
 * The opt-in research preview (financial.local-ai.research-preview.enabled, default false, 404 when disabled)
 * projects only whitelisted output-contract fields of stored selected outputs; the package is RESEARCH_ONLY
 * and has no publication policy yet. Inputs, features, artifacts and run payloads never leave the DB.
 */
@Service
@Transactional(readOnly=true,timeout=15,isolation=Isolation.REPEATABLE_READ)
public class UserLocalAiService {
  private final boolean previewEnabled;
  private final UserLocalAiQueryRepository queryRepo;
  private final UserReadSupport read;
  private final ObjectMapper json;

  @Autowired
  public UserLocalAiService(
      @Value("${financial.local-ai.research-preview.enabled:false}") boolean previewEnabled,
      UserLocalAiQueryRepository queryRepo,
      UserReadSupport read,
      ObjectMapper json) {
    this.previewEnabled = previewEnabled;
    this.queryRepo = queryRepo;
    this.read = read;
    this.json = json;
  }

  static final String NOTE = "Registered research progress only. Counts are stored records, not accuracy, live provider availability or public prediction approval. No model execution, feature submission or training occurs on reads.";

  boolean installed() {
    return queryRepo.isInstalled();
  }

  Map<String,Object> packageRow(UUID id) {
    return queryRepo.findPackageRow(id);
  }

  private Map<String,Object> project(Map<String,Object> row) {
    var result = new LinkedHashMap<>(row);
    result.put("readiness", "RESEARCH_ONLY");
    result.put("publicResultsAvailable", false);
    result.put("inputMode", "REGISTERED_SNAPSHOT");
    result.put("featureSubmissionAllowed", false);
    result.put("outputContract", contract(Objects.toString(row.get("kind"), "")));
    return result;
  }

  private Map<String,Object> contract(String kind) {
    return switch (kind) {
      case "h3" -> Map.of("type", "DIRECTION", "fields", List.of("direction", "score_up", "threshold"), "unit", "UNCALIBRATED_SCORE_0_1", "note", "UP or NOT_UP; score is not a calibrated probability. Read horizonQuarters, not the legacy kind name h3.");
      case "quantile" -> Map.of("type", "RETURN_QUANTILES", "fields", List.of("return_p10", "return_p50", "return_p90"), "unit", "FRACTION", "note", "Selected baseline may use pooled historical quantiles; these are not guaranteed per-security calibrated intervals.");
      case "npat" -> Map.of("type", "NET_PROFIT_AFTER_TAX", "fields", List.of("prediction_vnd"), "unit", "VND", "reportScope", "CONSOLIDATED", "periodBasis", "STANDALONE_QUARTER");
      case "return" -> Map.of("type", "ADJUSTED_PRICE_RETURN", "fields", List.of("prediction"), "unit", "ADJUSTED_PRICE_RETURN_FRACTION");
      case "volatility" -> Map.of("type", "VOLATILITY", "fields", List.of("prediction"), "unit", "ANNUALIZED_LOG_RETURN_VOLATILITY");
      case "drawdown" -> Map.of("type", "DRAWDOWN", "fields", List.of("prediction"), "unit", "MAX_CLOSE_DRAWDOWN_FRACTION");
      default -> Map.of("type", "UNSUPPORTED", "fields", List.of(), "note", "Unknown output contract; no prediction is exposed.");
    };
  }

  public Data<Map<String,Object>> summary() {
    boolean available = installed();
    var pkg = available ? packageRow(null) : null;
    var result = new LinkedHashMap<String,Object>();
    result.put("schemaAvailable", available);
    result.put("state", !available ? "NOT_INSTALLED" : pkg == null ? "NO_REGISTERED_PACKAGE" : "RESEARCH_ONLY");
    result.put("package", pkg);
    result.put("publicResultsAvailable", false);
    result.put("executionAvailable", false);
    result.put("modelsRoute", "/api/v1/local-ai/models");
    return data(result, NOTE, "A development gate is model selection evidence, not production eligibility or forward forecast accuracy.");
  }

  public Page<Map<String,Object>> models(UUID packageId, int page, int size) {
    paging(page, size, 100);
    if (!installed()) return new Page<>(List.of(), 0, page, size, false, meta(NOTE, "Suite schema is not installed."));
    var pkg = packageRow(packageId);
    if (pkg == null) return new Page<>(List.of(), 0, page, size, false, meta(NOTE, "No supported research package is registered."));
    var rows = queryRepo.queryModels(pkg, page, size, NOTE);
    return new Page<>(rows.items().stream().map(this::project).toList(), rows.total(), page, size, rows.hasNext(), rows.meta());
  }

  public Data<Map<String,Object>> security(String symbol, String exchange, UUID packageId) {
    var sec = read.resolve(symbol, exchange);
    var result = new LinkedHashMap<String,Object>();
    result.put("security", sec);
    result.put("publicResultsAvailable", false);
    result.put("executionAvailable", false);
    result.put("models", List.of());
    if (!installed()) {
      result.put("state", "NOT_INSTALLED");
      return data(result, NOTE);
    }
    var pkg = packageRow(packageId);
    result.put("package", pkg);
    if (pkg == null) {
      result.put("state", "NO_REGISTERED_PACKAGE");
      return data(result, NOTE);
    }
    String ticker = sec.get("symbol").toString();
    var identity = queryRepo.checkTickerIdentity(ticker);
    if (!"1".equals(identity.get("count").toString())) {
      result.put("state", "TICKER_IDENTITY_AMBIGUOUS");
      return data(result, NOTE, "Suite v1 identifies inputs by ticker without exchange or security UUID. No association is guessed when multiple listings share a ticker.");
    }
    var rows = queryRepo.querySecurityModels(pkg, ticker);
    result.put("modelsTruncated", rows.size() > 100);
    var modelsList = rows.stream().limit(100).map(this::project).toList();
    result.put("models", modelsList);
    boolean input = modelsList.stream().anyMatch(m -> Long.parseLong(m.get("registeredInputs").toString()) > 0);
    result.put("state", input ? "RESEARCH_ONLY" : "NO_REGISTERED_INPUT");
    return data(result, NOTE, "No stored result value, input features, train files, artifact paths, run payload or retrospective outcome is published. Missing ticker input is not filled from a different company.");
  }

  // ---- opt-in research preview ----
  static final String PREVIEW_NOTE = "RESEARCH PREVIEW ONLY. Stored outputs of a RESEARCH_ONLY package without publication approval. Not investment advice, not a calibrated probability or verified forecast accuracy. Reads never execute a model.";
  static final Map<String,List<String>> PREVIEW_FIELDS = Map.of(
    "h3", List.of("direction", "score_up", "threshold"),
    "quantile", List.of("return_p10", "return_p50", "return_p90"),
    "npat", List.of("prediction_vnd"),
    "return", List.of("prediction"),
    "volatility", List.of("prediction"),
    "drawdown", List.of("prediction"));

  public boolean previewEnabled() { return previewEnabled; }

  public Data<Map<String,Object>> preview(String symbol, String exchange, UUID packageId) {
    if (!previewEnabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local AI research preview is disabled");
    var sec = read.resolve(symbol, exchange);
    var result = new LinkedHashMap<String,Object>();
    result.put("security", sec);
    result.put("readiness", "RESEARCH_ONLY");
    result.put("researchPreview", true);
    result.put("publicResultsAvailable", false);
    result.put("predictions", List.of());
    if (!installed()) {
      result.put("state", "NOT_INSTALLED");
      return data(result, PREVIEW_NOTE);
    }
    var pkg = packageRow(packageId);
    result.put("package", pkg);
    if (pkg == null) {
      result.put("state", "NO_REGISTERED_PACKAGE");
      return data(result, PREVIEW_NOTE);
    }
    String ticker = sec.get("symbol").toString();
    var identity = queryRepo.checkTickerIdentity(ticker);
    if (!"1".equals(identity.get("count").toString())) {
      result.put("state", "TICKER_IDENTITY_AMBIGUOUS");
      return data(result, PREVIEW_NOTE);
    }
    var rows = queryRepo.queryPreviewPredictions(pkg, ticker).stream().map(this::projectPreview).filter(Objects::nonNull).toList();
    result.put("predictions", rows);
    result.put("state", rows.isEmpty() ? "NO_STORED_RESULT" : "RESEARCH_PREVIEW");
    return data(result, PREVIEW_NOTE, "Values are the latest stored selected output per model. Decimal values are strings. Direction score is uncalibrated (0-1).");
  }

  Map<String,Object> projectPreview(Map<String,Object> row) {
    var fields = PREVIEW_FIELDS.get(Objects.toString(row.get("kind"), ""));
    if (fields == null) return null;
    JsonNode node;
    try {
      node = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(Objects.toString(row.get("prediction"), "{}"));
    } catch (Exception e) {
      return null;
    }
    var values = new LinkedHashMap<String,Object>();
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull()) values.put(field, null);
      else if (value.isNumber()) values.put(field, value.decimalValue().toPlainString());
      else if (value.isTextual() && value.asText().length() <= 40) values.put(field, value.asText());
      else if (value.isBoolean()) values.put(field, value.asBoolean());
      else values.put(field, null);
    }
    var result = new LinkedHashMap<>(row);
    result.remove("prediction");
    result.put("values", values);
    result.put("readiness", "RESEARCH_ONLY");
    return result;
  }
}
