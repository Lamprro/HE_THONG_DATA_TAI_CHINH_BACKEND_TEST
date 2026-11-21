package com.hethongdata.taichinh.repository.jdbc.admin;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminConsoleQueryRepository {
  public record BrowseResult(List<String> rawRows, long total) {}

  private final NamedParameterJdbcTemplate db;

  public AdminConsoleQueryRepository(DataSource source) {
    var jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(15);
    this.db = new NamedParameterJdbcTemplate(jdbc);
  }

  public Map<String, Object> summary() {
    var jdbc = db.getJdbcTemplate();
    return Map.of(
        "jobs", jdbc.queryForList("select is_active,count(*) as count from ingestion_jobs group by is_active"),
        "commands", jdbc.queryForList("select status,count(*) as count from admin_commands group by status"),
        "runs", jdbc.queryForList("select status,count(*) as count from ingestion_runs group by status"),
        "versions", jdbc.queryForList("select data_domain,status,count(*) as count from data_versions group by data_domain,status"),
        "validation", jdbc.queryForList("select validation_target,result_status,count(*) as count from validation_results group by validation_target,result_status"));
  }

  public List<Map<String, Object>> articleTitles(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    var rows = new ArrayList<>(db.queryForList(
        "select id as \"articleId\",title,published_at from news_articles where id in (:ids)", Map.of("ids", ids)));
    rows.sort(Comparator.comparingInt(row -> ids.indexOf(row.get("articleId"))));
    return rows;
  }

  public List<Map<String, Object>> issueReviews(UUID validationResultId) {
    return db.getJdbcTemplate().queryForList(
        "select id,event_type,reason,principal,created_at from admin_operation_events "
            + "where target_id=? order by created_at desc,id desc", validationResultId);
  }

  public boolean databaseUp() {
    Integer one = db.getJdbcTemplate().queryForObject("select 1", Integer.class);
    return one != null && one == 1;
  }

  public List<String> columns(String table) {
    return db.queryForList("select column_name from information_schema.columns where table_schema=current_schema() "
        + "and table_name=:name order by ordinal_position", Map.of("name", table), String.class);
  }

  public List<String> searchableColumns(String table, List<String> visible) {
    return db.queryForList("select column_name from information_schema.columns where table_schema=current_schema() "
        + "and table_name=:table and data_type not in ('json','jsonb','bytea','ARRAY','USER-DEFINED') order by ordinal_position",
        Map.of("table", table), String.class).stream().filter(visible::contains).toList();
  }

  public BrowseResult browse(String table, List<String> cols, List<String> visible, List<String> searchable,
      int page, int size, String search, String runId, String status, Map<String, String> exact) {
    var labels = new LinkedHashMap<String, String>();
    if (cols.contains("security_id")) labels.put("symbol", "(select s.symbol from securities s where s.id=t.security_id)");
    if (cols.contains("company_id")) labels.put("company_name", "(select c.legal_name from companies c where c.id=t.company_id)");
    if (cols.contains("data_source_id")) labels.put("source_code", "(select s.code from data_sources s where s.id=t.data_source_id)");
    if (cols.contains("metric_definition_id")) labels.put("metric_code", "(select d.code from metric_definitions d where d.id=t.metric_definition_id)");
    if (cols.contains("financial_period_id")) labels.put("period_end", "(select p.end_date from financial_periods p where p.id=t.financial_period_id)");
    if (cols.contains("financial_statement_id")) {
      labels.put("symbol", "(select s.symbol from financial_statements f join securities s on s.id=f.security_id where f.id=t.financial_statement_id)");
      labels.put("period_end", "(select p.end_date from financial_statements f join financial_periods p on p.id=f.financial_period_id where f.id=t.financial_statement_id)");
      labels.put("statement_type", "(select f.statement_type from financial_statements f where f.id=t.financial_statement_id)");
    }

    var filters = new ArrayList<String>();
    var params = new HashMap<String, Object>();
    int index = 0;
    for (var entry : exact.entrySet()) {
      if (entry.getValue() == null || entry.getValue().isBlank()) continue;
      String key = "exact" + (index++);
      filters.add("cast(t." + entry.getKey() + " as text)=:" + key);
      params.put(key, entry.getValue());
    }

    if (search != null && !search.isBlank()) {
      var searches = new ArrayList<String>();
      searchable.forEach(c -> searches.add("cast(t.\"" + c + "\" as text) ilike :q escape '\\'"));
      if (cols.contains("security_id")) searches.add("t.security_id in (select s.id from securities s where s.symbol ilike :q escape '\\')");
      if (cols.contains("financial_statement_id")) searches.add("t.financial_statement_id in (select f.id from financial_statements f join securities s on s.id=f.security_id where s.symbol ilike :q escape '\\')");
      if (cols.contains("metric_definition_id")) searches.add("t.metric_definition_id in (select d.id from metric_definitions d where d.code ilike :q escape '\\')");
      filters.add(searches.isEmpty() ? "false" : "(" + String.join(" or ", searches) + ")");
      params.put("q", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }

    if (runId != null && !runId.isBlank()) {
      filters.add("t.ingestion_run_id=:runId");
      params.put("runId", UUID.fromString(runId));
    }

    if (status != null && !status.isBlank()) {
      String column = cols.contains("result_status") ? "result_status" : "status";
      if (status.contains(",")) {
        var parts = Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        var keys = new ArrayList<String>();
        for (int i = 0; i < parts.size(); i++) {
          String k = "status" + i;
          keys.add(":" + k);
          params.put(k, parts.get(i));
        }
        filters.add("cast(t." + column + " as text) in (" + String.join(",", keys) + ")");
      } else {
        filters.add("cast(t." + column + " as text)=:status");
        params.put("status", status);
      }
    }

    String where = filters.isEmpty() ? "" : " where " + String.join(" and ", filters);
    String source = " from " + table + " t" + where;
    Long totalCount = db.queryForObject("select count(*)" + source, params, Long.class);
    long total = totalCount == null ? 0L : totalCount;

    String projection = "jsonb_build_object(" + visible.stream()
        .map(c -> "'" + c + "',t.\"" + c + "\"").collect(java.util.stream.Collectors.joining(",")) + ")";
    if (!labels.isEmpty()) {
      projection += " || jsonb_build_object(" + labels.entrySet().stream()
          .map(e -> "'" + e.getKey() + "'," + e.getValue()).collect(java.util.stream.Collectors.joining(",")) + ")";
    }

    String time = List.of("created_at", "started_at", "registered_at", "observed_at").stream().filter(cols::contains).findFirst().orElse(null);
    String order = time == null ? "" : "t." + time + " desc nulls last,";
    order += cols.contains("id") ? "t.id desc" : "cast(" + projection + " as text)";

    params.put("limit", size);
    params.put("offset", (long) page * size);

    var rows = db.query("select cast(" + projection + " as text)" + source + " order by " + order + " limit :limit offset :offset",
        params, (rs, n) -> rs.getString(1));

    return new BrowseResult(rows, total);
  }
}
