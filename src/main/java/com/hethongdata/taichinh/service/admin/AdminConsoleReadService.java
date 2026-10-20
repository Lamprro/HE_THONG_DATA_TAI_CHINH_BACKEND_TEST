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
      "prediction_outcomes", "admin_commands", "admin_operation_events");
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
    return browse(table,page,size,search,runId,status,Map.of());
  }
  public Map<String,Object> browse(String table, int page, int size, String search, String runId, String status,Map<String,String> exact) {
    if (!TABLES.contains(table)) throw new IllegalArgumentException("Dataset is not available in the admin browser");
    if (page < 0 || size < 1 || size > 100 || page > 100000) throw new IllegalArgumentException("Invalid page or size (1..100)");
    var cols = columns(table);
    if (cols.isEmpty()) throw new IllegalArgumentException("Dataset is not installed on this deployment");
    var labels = new LinkedHashMap<String,String>();
    if(cols.contains("security_id")) labels.put("symbol","(select s.symbol from securities s where s.id=t.security_id)");
    if(cols.contains("company_id")) labels.put("company_name","(select c.legal_name from companies c where c.id=t.company_id)");
    if(cols.contains("data_source_id")) labels.put("source_code","(select s.code from data_sources s where s.id=t.data_source_id)");
    if(cols.contains("metric_definition_id")) labels.put("metric_code","(select d.code from metric_definitions d where d.id=t.metric_definition_id)");
    if(cols.contains("financial_period_id")) labels.put("period_end","(select p.end_date from financial_periods p where p.id=t.financial_period_id)");
    if(cols.contains("financial_statement_id")) {
      labels.put("symbol","(select s.symbol from financial_statements f join securities s on s.id=f.security_id where f.id=t.financial_statement_id)");
      labels.put("period_end","(select p.end_date from financial_statements f join financial_periods p on p.id=f.financial_period_id where f.id=t.financial_statement_id)");
      labels.put("statement_type","(select f.statement_type from financial_statements f where f.id=t.financial_statement_id)");
    }
    var filters = new ArrayList<String>(); var params = new HashMap<String,Object>();
    int index=0;
    for(var entry:exact.entrySet()) {
      if(entry.getValue()==null || entry.getValue().isBlank()) continue;
      if(!Set.of("id","raw_payload_id","data_version_id","validation_target","llm_run_id","is_canonical","is_current","action","ingestion_job_id","security_id","data_source_id","parent_command_id").contains(entry.getKey()) || !cols.contains(entry.getKey()))
        throw new IllegalArgumentException("Unsupported dataset filter: "+entry.getKey());
      String key="exact"+(index++);filters.add("cast(t."+entry.getKey()+" as text)=:"+key);params.put(key,entry.getValue());
    }
    if (search != null && !search.isBlank()) {
      if (search.length()>100) throw new IllegalArgumentException("Search is limited to 100 characters");
      var searches=new ArrayList<String>(); searches.add("cast(to_jsonb(t) as text) ilike :q escape '\\'");
      if(cols.contains("security_id")) searches.add("t.security_id in (select s.id from securities s where s.symbol ilike :q escape '\\')");
      if(cols.contains("financial_statement_id")) searches.add("t.financial_statement_id in (select f.id from financial_statements f join securities s on s.id=f.security_id where s.symbol ilike :q escape '\\')");
      if(cols.contains("metric_definition_id")) searches.add("t.metric_definition_id in (select d.id from metric_definitions d where d.code ilike :q escape '\\')");
      filters.add("("+String.join(" or ",searches)+")");
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
    if(!labels.isEmpty()) projection += " || jsonb_build_object("+labels.entrySet().stream()
        .map(e -> "'"+e.getKey()+"',"+e.getValue()).collect(java.util.stream.Collectors.joining(","))+")";
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
