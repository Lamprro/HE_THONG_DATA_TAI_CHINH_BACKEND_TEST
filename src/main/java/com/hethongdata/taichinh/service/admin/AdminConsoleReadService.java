package com.hethongdata.taichinh.service.admin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;
import com.hethongdata.taichinh.repository.jpa.account.AccountJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.hethongdata.taichinh.repository.jpa.admin.AdminConsoleQueryRepository;
import java.util.*;
import org.springframework.stereotype.Service;

/** Bounded, read-only browsing. Identifiers come exclusively from the server allowlist/catalog. */
@Service
public class AdminConsoleReadService {
  public static final List<String> TABLES = List.of("ingestion_runs", "raw_payloads", "validation_results",
      "validation_rules", "data_versions", "market_prices", "financial_statements", "financial_statement_items",
      "financial_periods", "financial_metrics", "metric_definitions", "market_indices", "index_prices",
      "security_index_memberships", "macro_series", "macro_observations", "news_articles", "llm_runs",
      "llm_run_attempts", "llm_validation_rounds", "llm_results", "llm_prompt_templates",
      "security_foreign_observations", "security_reference_observations",
      "admin_commands");
  private static final Set<String> OMIT = Set.of("body", "payload", "raw_text", "payload_json", "request_headers", "response_headers",
      "response_snapshot", "response_text", "request_body");
  private static final Set<String> EXACT = Set.of("id","raw_payload_id","data_version_id","validation_target","llm_run_id",
      "is_canonical","is_current","action","ingestion_job_id","security_id","data_source_id","parent_command_id",
      "run_id","request_id","symbol","source_raw_payload_id","report_scope","revision_status");
  private Set<String> omitted(String table) {
    var result=new HashSet<>(OMIT);
    return result;
  }
  private final AdminConsoleQueryRepository repository;
  private final ValidationResultJpaRepository validation;
  private final AccountJpaRepository accounts;
  private final ObjectMapper json;

  public AdminConsoleReadService(AdminConsoleQueryRepository repository, ValidationResultJpaRepository validation,
      AccountJpaRepository accounts, ObjectMapper json) {
    this.repository = repository;
    this.validation = validation;
    this.accounts = accounts;
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
  }
  /** Whole-database counts for the overview; never a sample of recent rows. */
  public Summary summary() {
    return repository.summary();
  }
  /** Titles for article ids, preserving the caller's order. */
  public List<NewsCandidate> articleTitles(List<UUID> ids) {
    return repository.articleTitles(ids);
  }
  public List<IssueReview> issueReviews(UUID id) {
    var result = validation.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (result.getResolutionNote() == null) return List.of();
    String principal = result.getResolvedByUserId() == null ? null : accounts.findByUserId(result.getResolvedByUserId())
        .map(account -> account.getUsername()).orElse(null);
    return List.of(new IssueReview(id, "ISSUE_REVIEWED", result.getResolutionNote(), principal, result.getResolvedAt()));
  }

  @Transactional
  public ReviewReceipt reviewIssue(UUID id, String note, String username) {
    if (note == null || note.isBlank() || note.length() > 2000) {
      throw new IllegalArgumentException("A reason (1..2000) is required");
    }
    var account = accounts.findTop2ByUsername(username);
    if (account.size() != 1 || account.getFirst().getUserId() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin account has no unique user profile");
    }
    var result = validation.findForReview(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    result.recordReview(account.getFirst().getUserId(), note);
    return new ReviewReceipt(id, id, false);
  }

  public boolean databaseUp() {
    return repository.databaseUp();
  }
  public List<Map<String, Object>> tables() {
    return TABLES.stream().map(name -> {
      var columns = columns(name);
      return Map.<String,Object>of("name", name, "available", !columns.isEmpty(), "columns", columns,
          "exactFilters",columns.stream().filter(EXACT::contains).toList(),"omittedColumns",omitted(name));
    }).toList();
  }
  private List<String> columns(String name) {
    return repository.columns(name);
  }
  public Map<String,Object> browse(String table, int page, int size, String search, String runId, String status) {
    return browse(table,page,size,search,runId,status,Map.of());
  }
  public Map<String,Object> browse(String table, int page, int size, String search, String runId, String status,Map<String,String> exact) {
    if (!TABLES.contains(table)) throw new IllegalArgumentException("Dataset is not available in the admin browser");
    if (page < 0 || size < 1 || size > 100 || page > 100000) throw new IllegalArgumentException("Invalid page or size (1..100)");
    var cols = columns(table);
    if (cols.isEmpty()) throw new IllegalArgumentException("Dataset is not installed on this deployment");
    var omitted=omitted(table);
    List<String> visible = cols.stream().filter(c -> !omitted.contains(c) && !sensitive(c)).toList();
    var searchable = repository.searchableColumns(table, visible);

    for(var entry:exact.entrySet()) {
      if(entry.getValue()==null || entry.getValue().isBlank()) continue;
      if(!EXACT.contains(entry.getKey()) || !cols.contains(entry.getKey()))
        throw new IllegalArgumentException("Unsupported dataset filter: "+entry.getKey());
      if(entry.getValue().length()>200) throw new IllegalArgumentException("Exact filter is limited to 200 characters");
    }
    if (search != null && !search.isBlank() && search.length()>100) {
      throw new IllegalArgumentException("Search is limited to 100 characters");
    }
    if (runId != null && !runId.isBlank() && !cols.contains("ingestion_run_id")) {
      throw new IllegalArgumentException("Dataset has no ingestion run filter");
    }
    if (status != null && !status.isBlank()) {
      String column = cols.contains("result_status") ? "result_status" : "status";
      if (!cols.contains(column)) throw new IllegalArgumentException("Dataset has no status filter");
    }

    var result = repository.browse(table, cols, visible, searchable, page, size, search, runId, status, exact);
    var rows = result.rawRows().stream().map(this::decode).toList();
    return Map.of("rows", rows, "total", result.total(), "page", page, "size", size,
        "columns", visible, "numberEncoding", "decimal strings preserve precision", "omittedColumns", omitted);
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
