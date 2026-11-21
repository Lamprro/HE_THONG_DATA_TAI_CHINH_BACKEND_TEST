package com.hethongdata.taichinh.service.user;

import com.hethongdata.taichinh.repository.jdbc.user.UserFinancialQueryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/** Financial statements, metrics, period matrix views, metric comparison/screening, periods and statement/metric lineage. */
@Service
@Transactional(readOnly=true,timeout=15,isolation=Isolation.REPEATABLE_READ)
public class UserFinancialService {
  private final UserFinancialQueryRepository queryRepo;
  private final UserReadSupport read;

  public UserFinancialService(UserFinancialQueryRepository queryRepo, UserReadSupport read) {
    this.queryRepo = queryRepo;
    this.read = read;
  }

  static final String NOTE="Current canonical reports only. Unknown publication dates, report scope and cumulative/standalone basis stay unknown; no point-in-time or TTM certification.";
  static final String COMPARISON_NOTE="Latest stored metric on or before asOf, not a verified publication-time snapshot. Origin is selected explicitly; multiple canonical candidates at the latest date return AMBIGUOUS and no value. Units and dates can differ; no composite score or zero imputation.";

  private UUID companyId(String symbol, String exchange) {
    return UUID.fromString(read.resolve(symbol, exchange).get("companyId").toString());
  }

  private String period(String type) {
    return option(type, Set.of("FY", "Q1", "Q2", "Q3", "Q4"));
  }

  // ---- statements and metrics ----
  public Page<Map<String,Object>> statements(String symbol,String exchange,String type,String period,LocalDate from,LocalDate to,int page,int size) {
    var company = companyId(symbol, exchange);
    var range = window(from, to, 10, 30);
    String statement = option(type, Set.of("BALANCE_SHEET", "INCOME_STATEMENT", "CASH_FLOW"));
    String cycle = period(period);
    return queryRepo.queryStatements(company, statement, cycle, range.from(), range.to(), page, size, NOTE);
  }

  public Data<Map<String,Object>> statement(String symbol,String exchange,UUID id) {
    var report = new LinkedHashMap<>(publishedReport(symbol, exchange, id));
    var items = queryRepo.findStatementItems(id);
    if (items.size() > 2000) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "Statement exceeds 2000 display items");
    }
    report.put("items", items);
    return data(report, NOTE, "Read each item's stored unit. Do not multiply monetary values by report unitScale without checking the source normalization contract.");
  }

  public Map<String,Object> publishedReport(String symbol,String exchange,UUID id) {
    return queryRepo.findPublishedReport(companyId(symbol, exchange), id);
  }

  public Page<Map<String,Object>> definitions(int page,int size) {
    return queryRepo.queryDefinitions(page, size);
  }

  public Page<Map<String,Object>> metrics(String symbol,String exchange,String codes,String origin,String period,LocalDate from,LocalDate to,int page,int size) {
    var company = companyId(symbol, exchange);
    var range = window(from, to, 10, 30);
    List<String> values = null;
    if (codes != null && !codes.isBlank()) {
      values = Arrays.stream(codes.split(",", -1)).map(UserReadSupport::code).distinct().toList();
      if (values.size() > 20) throw new IllegalArgumentException("At most 20 metric codes");
    }
    String selected = option(origin, Set.of("PROVIDER", "DERIVED"));
    Boolean derived = selected != null ? "DERIVED".equals(selected) : null;
    String cycle = period(period);
    return queryRepo.queryMetrics(company, values, derived, cycle, range.from(), range.to(), page, size);
  }

  // ---- statement item catalog and period matrix ----
  private String type(String value) {
    String result = option(value, Set.of("BALANCE_SHEET", "INCOME_STATEMENT", "CASH_FLOW"));
    if (result == null) throw new IllegalArgumentException("statement type required");
    return result;
  }

  public Page<Map<String,Object>> items(String symbol,String exchange,String type,int page,int size) {
    return queryRepo.queryItems(companyId(symbol, exchange), type(type), page, size);
  }

  public Data<Map<String,Object>> matrix(String symbol,String exchange,String type,String period,String scope,String codes,LocalDate from,LocalDate to,int limit) {
    if (limit < 1 || limit > 40) throw new IllegalArgumentException("periodLimit must be 1..40");
    var selected = codes(codes, 20);
    var security = read.resolve(symbol, exchange);
    var range = window(from, to, 10, 30);
    var company = UUID.fromString(security.get("companyId").toString());
    String statementType = type(type);
    String cycle = option(period == null ? "QUARTER" : period, Set.of("QUARTER", "FY", "Q1", "Q2", "Q3", "Q4"));
    if (cycle == null) throw new IllegalArgumentException("period required");
    String requestedScope = option(scope, Set.of("CONSOLIDATED", "SEPARATE", "UNKNOWN"));

    var periods = new ArrayList<>(queryRepo.findMatrixPeriods(company, statementType, cycle, requestedScope, range.from(), range.to(), limit));
    boolean earlier = periods.size() > limit;
    if (earlier) periods.removeLast();
    Collections.reverse(periods);

    var series = new LinkedHashMap<List<Object>, Map<String, Object>>();
    var found = new HashSet<String>();
    if (!periods.isEmpty()) {
      var periodIds = periods.stream().map(p -> UUID.fromString(p.get("id").toString())).toList();
      var rows = queryRepo.findMatrixSeriesRows(company, statementType, cycle, requestedScope, range.from(), range.to(), periodIds, selected);
      for (var row : rows) {
        List<Object> key = Arrays.asList(row.get("code"), row.get("scope"), row.get("basis"), row.get("currency"), row.get("unit"), row.get("unitScale"));
        var line = series.computeIfAbsent(key, k -> {
          var item = new LinkedHashMap<String, Object>();
          for (String field : List.of("code", "name", "unit", "scope", "basis", "currency", "unitScale")) item.put(field, row.get(field));
          item.put("values", new LinkedHashMap<String, Object>());
          return item;
        });
        @SuppressWarnings("unchecked") var values = (Map<String, Object>) line.get("values");
        var point = new LinkedHashMap<String, Object>();
        for (String field : List.of("value", "statementId", "sourceCode", "publishedAt")) point.put(field, row.get(field));
        if (values.putIfAbsent(row.get("periodId").toString(), point) != null) {
          throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Multiple canonical cells for the same period/scope/unit");
        }
        found.add(row.get("code").toString());
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("security", security);
    result.put("statementType", statementType);
    result.put("periods", periods);
    result.put("series", List.copyOf(series.values()));
    result.put("hasEarlierPeriods", earlier);
    result.put("missingCodes", selected.stream().filter(c -> !found.contains(c)).toList());
    return data(result, NOTE, "Cells are stored values; different report scope, period basis, currency and unit remain separate series. Missing cells stay absent, never zero.", "This is a current-canonical period view, not a historical-vintage backtest. Quarterly and annual series are never combined by default.");
  }

  // ---- periods and lineage ----
  public Data<List<Map<String,Object>>> periods(String symbol,String exchange) {
    var rows = queryRepo.findReportingPeriods(companyId(symbol, exchange));
    if (rows.size() > 500) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "More than 500 reporting periods; use paginated statements");
    }
    return data(rows, "Only periods with current canonical activated reports. A period end is not a filing date.");
  }

  public Data<Map<String,Object>> lineage(String symbol,String exchange,UUID report) {
    publishedReport(symbol, exchange, report); // Same visibility/ownership gate, without loading report items.
    var result = queryRepo.findStatementLineage(report);
    result.put("sourceUrl", publicSourceUrl(result.get("sourceUrl")));
    return data(result, "Public lineage projection only; raw bodies, headers and ingestion parameters are omitted. Source links with credentials, queries or fragments are not exposed.");
  }

  /** Sources for the exact metric observation selected in the UI, never a latest-row substitute. */
  public Data<Map<String,Object>> metricLineage(String symbol,String exchange,UUID id) {
    var security = read.resolve(symbol, exchange);
    var row = queryRepo.findMetricLineage(id, UUID.fromString(security.get("companyId").toString()));
    row.put("sourceUrl", publicSourceUrl(row.get("sourceUrl")));
    row.put("origin", Boolean.TRUE.equals(row.get("derived")) ? "DERIVED" : "PROVIDER");
    row.put("pointInTimeCertified", false);
    return data(row, "Same publication and company ownership rules as the metric list. observedSecurityId identifies the stored listing when present.",
        "catalogFormula is the current definition, not proof of a provider formula or the historical calculation. calculationVersion identifies the stored Java calculation; raw input snapshots remain private.",
        "asOfDate is not a verified release date. Source links may be null; missing period, publication and version evidence is not inferred.");
  }

  // ---- comparison and screener ----
  private boolean isDerived(String origin) {
    String selected = option(origin == null ? "PROVIDER" : origin, Set.of("PROVIDER", "DERIVED"));
    if (selected == null) throw new IllegalArgumentException("origin required");
    return "DERIVED".equals(selected);
  }

  private BigDecimal bound(String value) {
    if (value == null) return null;
    if (value.length() > 60) throw new IllegalArgumentException("Metric bound is too long");
    try {
      var n = new BigDecimal(value);
      if (n.precision() > 38 || n.scale() > 12 || n.scale() < -38) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Metric bounds must be finite decimals with precision <= 38 and scale <= 12");
    }
  }

  public Page<Map<String,Object>> screen(String search,String exchange,String industry,String metric,String origin,LocalDate asOf,String min,String max,String sort,int page,int size) {
    paging(page, size, 100);
    boolean derived = isDerived(origin);
    var range = window(asOf, asOf, 0, 0);
    var searchTerm = search(search);
    var metricCode = metric == null ? null : code(metric);
    var lower = bound(min);
    var upper = bound(max);
    if (lower != null && upper != null && lower.compareTo(upper) > 0) throw new IllegalArgumentException("min must be <= max");
    String order = option(sort == null ? "SYMBOL" : sort, Set.of("SYMBOL", "METRIC_ASC", "METRIC_DESC"));
    if (order == null) throw new IllegalArgumentException("sort required");
    if (metricCode == null && (lower != null || upper != null || !order.equals("SYMBOL"))) {
      throw new IllegalArgumentException("metric is required for bounds or metric sorting");
    }
    if (metricCode != null) {
      queryRepo.verifyMetricCode(metricCode);
    }
    String exchangeCode = (exchange != null && !exchange.isBlank()) ? code(exchange) : null;
    if (industry != null && (industry.isBlank() || industry.length() > 200)) {
      throw new IllegalArgumentException("industry must be 1..200 characters");
    }
    String ordering = switch (order) {
      case "METRIC_ASC" -> "metric.value asc nulls last,";
      case "METRIC_DESC" -> "metric.value desc nulls last,";
      default -> "";
    };
    return queryRepo.screenMetrics(searchTerm, exchangeCode, industry, metricCode, derived, range.to(), lower, upper, ordering, page, size, COMPARISON_NOTE);
  }

  public Data<Map<String,Object>> compare(String symbols,String exchange,String codes,String origin,LocalDate asOf) {
    var selected = codes(codes, 20);
    var requested = codes(symbols, 10);
    boolean derived = isDerived(origin);
    var range = window(asOf, asOf, 0, 0);
    var profiles = requested.stream().map(symbol -> read.resolve(symbol, exchange)).toList();
    var securityIds = profiles.stream().map(s -> UUID.fromString(s.get("id").toString())).toList();
    var definitions = queryRepo.findMetricDefinitionsByCodes(selected);
    var known = definitions.stream().map(d -> d.get("code").toString()).toList();
    var rows = queryRepo.findSecurityMetricRows(securityIds, selected, derived, range.to());
    var items = new ArrayList<Map<String, Object>>();
    for (var profile : profiles) {
      var item = new LinkedHashMap<>(profile);
      item.put("metrics", rows.stream().filter(r -> r.get("securityId").equals(profile.get("id"))).toList());
      items.add(item);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("asOf", range.to());
    result.put("origin", derived ? "DERIVED" : "PROVIDER");
    result.put("definitions", definitions);
    result.put("unknownCodes", selected.stream().filter(c -> !known.contains(c)).toList());
    result.put("securities", items);
    return data(result, COMPARISON_NOTE);
  }
}
