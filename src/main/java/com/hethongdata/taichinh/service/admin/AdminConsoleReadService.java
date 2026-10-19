package com.hethongdata.taichinh.service.admin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded, read-only browsing. Identifiers come exclusively from the server allowlist/catalog. */
@Service
public class AdminConsoleReadService {
  public static final List<String> TABLES = List.of("ingestion_runs", "raw_payloads", "validation_results",
      "validation_rules", "data_versions", "market_prices", "financial_statements", "financial_statement_items",
      "financial_periods", "financial_metrics", "metric_definitions", "market_indices", "index_prices",
      "security_index_memberships", "macro_series", "macro_observations", "news_articles", "llm_runs",
      "llm_run_attempts", "llm_validation_rounds", "llm_results", "llm_prompt_templates",
      "security_foreign_observations", "security_reference_observations", "predictions",
      "prediction_outcomes", "admin_commands");
  private static final Set<String> OMIT = Set.of("body", "payload", "raw_text", "payload_json", "request_headers", "response_headers",
      "response_snapshot", "response_text", "request_body");
  private final NamedParameterJdbcTemplate db;
  private final ObjectMapper json;
  public AdminConsoleReadService(DataSource source, ObjectMapper json) {
    var jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(15);
    this.db = new NamedParameterJdbcTemplate(jdbc);
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
  }
  public List<Map<String, Object>> tables() {
    return TABLES.stream().map(name -> {
      var columns = columns(name);
      return Map.<String,Object>of("name", name, "available", !columns.isEmpty(), "columns", columns);
    }).toList();
  }
  private List<String> columns(String name) {
    return db.queryForList("select column_name from information_schema.columns where table_schema=current_schema() "
        + "and table_name=:name order by ordinal_position", Map.of("name", name), String.class);
  }
  public Map<String,Object> browse(String table, int page, int size, String search, String runId, String status) {
    if (!TABLES.contains(table)) throw new IllegalArgumentException("Dataset is not available in the admin browser");
    if (page < 0 || size < 1 || size > 100 || page > 100000) throw new IllegalArgumentException("Invalid page or size (1..100)");
    var cols = columns(table);
    if (cols.isEmpty()) throw new IllegalArgumentException("Dataset is not installed on this deployment");
    var filters = new ArrayList<String>(); var params = new HashMap<String,Object>();
    if (search != null && !search.isBlank()) {
      if (search.length()>100) throw new IllegalArgumentException("Search is limited to 100 characters");
      filters.add("cast(to_jsonb(t) as text) ilike :q escape '\\'");
      params.put("q", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }
    if (runId != null && !runId.isBlank()) {
      if (!cols.contains("ingestion_run_id")) throw new IllegalArgumentException("Dataset has no ingestion run filter");
      filters.add("t.ingestion_run_id=:runId"); params.put("runId", UUID.fromString(runId));
    }
    if (status != null && !status.isBlank()) {
      String column = cols.contains("result_status") ? "result_status" : "status";
      if (!cols.contains(column)) throw new IllegalArgumentException("Dataset has no status filter");
      filters.add("cast(t."+column+" as text)=:status"); params.put("status", status);
    }
    String where = filters.isEmpty() ? "" : " where " + String.join(" and ", filters);
    String source = " from " + table + " t" + where;
    long total = db.queryForObject("select count(*)"+source, params, Long.class);
    List<String> visible = cols.stream().filter(c -> !OMIT.contains(c) && !sensitive(c)).toList();
    String projection = "jsonb_build_object(" + visible.stream()
        .map(c -> "'"+c+"',t.\""+c+"\"").collect(java.util.stream.Collectors.joining(",")) + ")";
    String order = cols.contains("created_at") ? "t.created_at desc nulls last," : "";
    order += cols.contains("id") ? "t.id desc" : "cast(to_jsonb(t) as text)";
    params.put("limit", size); params.put("offset", (long)page*size);
    var rows = db.query("select cast("+projection+" as text)"+source+" order by "+order+" limit :limit offset :offset",
        params, (rs,n) -> decode(rs.getString(1)));
    return Map.of("rows", rows, "total", total, "page", page, "size", size,
        "columns", visible, "numberEncoding", "decimal strings preserve precision", "omittedColumns", OMIT);
  }
  public JsonNode decode(String value) {
    try { return safe(json.readTree(value)); }
    catch (Exception e) { throw new IllegalStateException("Invalid stored JSON"); }
  }
  public static boolean sensitive(String key) {
    return key.toLowerCase(Locale.ROOT).matches(".*(token|secret|password|authorization|cookie|credential|api.?key|private.?key).*" );
  }
  public static JsonNode safe(JsonNode value) {
    if (value.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      value.fields().forEachRemaining(e -> out.set(e.getKey(), sensitive(e.getKey())
          ? TextNode.valueOf("[redacted]") : safe(e.getValue()))); return out;
    }
    if (value.isArray()) { var out=JsonNodeFactory.instance.arrayNode(); value.forEach(v -> out.add(safe(v))); return out; }
    if (value.isNumber()) return TextNode.valueOf(value.asText());
    if (value.isTextual()) return TextNode.valueOf(value.asText()
        .replaceAll("(?i)Bearer\\s+[^\\s\"']+", "Bearer [redacted]")
        .replaceAll("(?i)((?:token|api_key|apiKey|password|secret)=)[^&\\s]+", "$1[redacted]"));
    return value;
  }
}
