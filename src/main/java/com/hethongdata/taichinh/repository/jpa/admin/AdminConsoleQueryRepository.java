package com.hethongdata.taichinh.repository.jpa.admin;

import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class AdminConsoleQueryRepository {
  public record BrowseResult(List<String> rawRows, long total) {}

  private final EntityManager entities;

  public AdminConsoleQueryRepository(EntityManager entities) {
    this.entities = entities;
  }

  public Summary summary() {
    var jobs = rows("select is_active,count(*) from ingestion_jobs group by is_active", Object[].class, Map.of())
        .stream().map(row -> new JobCount((Boolean) row[0], count(row[1]))).toList();
    var versions = rows("select data_domain,status,count(*) from data_versions group by data_domain,status",
        Object[].class, Map.of()).stream()
        .map(row -> new VersionCount((String) row[0], (String) row[1], count(row[2]))).toList();
    var validation = rows("""
        select validation_target,result_status,count(*) from validation_results group by validation_target,result_status
        """, Object[].class, Map.of()).stream()
        .map(row -> new ValidationCount((String) row[0], (String) row[1], count(row[2]))).toList();
    return new Summary(jobs,
        statusCounts("select status,count(*) from admin_commands group by status"),
        statusCounts("select status,count(*) from ingestion_runs group by status"),
        versions, validation);
  }

  private List<StatusCount> statusCounts(String sql) {
    return rows(sql, Object[].class, Map.of()).stream()
        .map(row -> new StatusCount((String) row[0], count(row[1]))).toList();
  }

  public List<NewsCandidate> articleTitles(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return rows("select id,title,cast(extract(epoch from published_at)*1000000 as bigint) from news_articles where id in (:ids)", Object[].class, Map.of("ids", ids))
        .stream().map(row -> new NewsCandidate((UUID) row[0], (String) row[1],
            row[2] == null ? null : java.time.Instant.EPOCH.plus(count(row[2]), java.time.temporal.ChronoUnit.MICROS)))
        .sorted(Comparator.comparingInt(row -> ids.indexOf(row.articleId()))).toList();
  }

  public boolean databaseUp() {
    return count(query("select 1", Integer.class, Map.of()).getSingleResult()) == 1;
  }

  public List<String> columns(String table) {
    return rows("select column_name from information_schema.columns where table_schema=current_schema() "
        + "and table_name=:name order by ordinal_position", String.class, Map.of("name", table));
  }

  public List<String> searchableColumns(String table, List<String> visible) {
    return rows("select column_name from information_schema.columns where table_schema=current_schema() "
        + "and table_name=:table and data_type not in ('json','jsonb','bytea','ARRAY','USER-DEFINED') order by ordinal_position",
        String.class, Map.of("table", table)).stream().filter(visible::contains).toList();
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
      searchable.forEach(c -> searches.add("cast(t.\"" + c + "\" as text) ilike :q escape chr(92)"));
      if (cols.contains("security_id")) searches.add("t.security_id in (select s.id from securities s where s.symbol ilike :q escape chr(92))");
      if (cols.contains("financial_statement_id")) searches.add("t.financial_statement_id in (select f.id from financial_statements f join securities s on s.id=f.security_id where s.symbol ilike :q escape chr(92))");
      if (cols.contains("metric_definition_id")) searches.add("t.metric_definition_id in (select d.id from metric_definitions d where d.code ilike :q escape chr(92))");
      filters.add(searches.isEmpty() ? "false" : "(" + String.join(" or ", searches) + ")");
      if (!searches.isEmpty()) params.put("q", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
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
        filters.add(keys.isEmpty() ? "false" : "cast(t." + column + " as text) in (" + String.join(",", keys) + ")");
      } else {
        filters.add("cast(t." + column + " as text)=:status");
        params.put("status", status);
      }
    }

    String where = filters.isEmpty() ? "" : " where " + String.join(" and ", filters);
    String source = " from " + table + " t" + where;
    long total = count(query("select count(*)" + source, Long.class, params).getSingleResult());

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

    var rows = rows("select cast(" + projection + " as text)" + source + " order by " + order + " limit :limit offset :offset",
        String.class, params);

    return new BrowseResult(rows, total);
  }

  private <T> List<T> rows(String sql, Class<T> type, Map<String, ?> parameters) {
    List<?> results = query(sql, type, parameters).getResultList();
    return results.stream().map(type::cast).toList();
  }

  private Query query(String sql, Class<?> type, Map<String, ?> parameters) {
    var query = entities.createNativeQuery(sql, type);
    query.setHint("jakarta.persistence.query.timeout", 15_000);
    parameters.forEach(query::setParameter);
    return query;
  }

  private long count(Object value) {
    return ((Number) value).longValue();
  }
}
