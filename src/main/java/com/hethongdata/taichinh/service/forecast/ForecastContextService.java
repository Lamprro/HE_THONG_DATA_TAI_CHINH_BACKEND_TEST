package com.hethongdata.taichinh.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.forecast.*;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import com.hethongdata.taichinh.service.financial.FinancialRatioCalculator;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.LlmJson;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastContextService {
  public record Context(
      UUID company,
      ForecastRequest request,
      LocalDate targetDate,
      List<SourcePoint> points,
      List<DerivedMetric> metrics,
      Coverage coverage,
      List<String> issues,
      JsonNode input,
      String hash) {
    public boolean eligible() {
      return issues.isEmpty();
    }
  }

  private final JdbcTemplate db;
  private final LlmJson json;
  private final ChecksumService hashes;
  private final FinancialRatioCalculator calculator;

  public ForecastContextService(
      JdbcTemplate db, LlmJson json, ChecksumService hashes, FinancialRatioCalculator calculator) {
    this.db = db;
    this.json = json;
    this.hashes = hashes;
    this.calculator = calculator;
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Context build(ForecastRequest request) {
    if (request.asOfDate() == null
        || request.asOfDate().isAfter(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))))
      throw new IllegalArgumentException("asOfDate must not be in the future");
    if (request.horizonQuarters() < 1
        || request.horizonQuarters() > 8
        || request.targets() == null
        || request.targets().isEmpty())
      throw new IllegalArgumentException("Choose 1-8 quarters and at least one supported target");
    var security =
        db.queryForList(
            "SELECT s.company_id,s.symbol,c.legal_name,c.industry_name FROM securities s JOIN"
                + " companies c ON c.id=s.company_id WHERE s.id=?",
            request.securityId());
    if (security.isEmpty()) throw new IllegalArgumentException("Security not found");
    UUID company = (UUID) security.getFirst().get("company_id");
    var cutoff =
        java.sql.Timestamp.from(
            request.asOfDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
    LocalDate start = request.asOfDate().minusYears(5);
    var points =
        new ArrayList<SourcePoint>(
            db.query(
                """
SELECT i.id,s.statement_type,i.item_code,i.value*s.unit_scale AS amount,s.currency,p.start_date,p.end_date,
    p.period_type,s.report_scope,coalesce(s.published_at,s.created_at)::text AS available_at
FROM financial_statement_items i JOIN financial_statements s ON s.id=i.financial_statement_id
JOIN financial_periods p ON p.id=s.financial_period_id
WHERE s.company_id=? AND s.security_id=? AND s.is_current AND s.is_canonical
    AND s.currency='VND' AND s.unit_scale>0 AND i.unit='VND' AND i.value IS NOT NULL
    AND p.end_date BETWEEN ? AND ? AND coalesce(s.published_at,s.created_at)<?
    AND i.created_at<? AND p.period_type IN ('Q1','Q2','Q3','Q4')
    AND ((s.statement_type='INCOME_STATEMENT' AND i.item_code IN ('NET_PROFIT_AFTER_TAX','PRETAX_PROFIT'))
        OR (s.statement_type='BALANCE_SHEET' AND i.item_code IN ('TOTAL_ASSETS','OWNERS_EQUITY',
            'LIABILITIES','CURRENT_ASSETS','SHORT_TERM_LIABILITIES','CASH_AND_CASH_EQUIVALENTS'))
        OR (s.statement_type='CASH_FLOW' AND i.item_code IN ('NET_CASHFLOW_FROM_OPERATING_ACTIVITIES',
            'NET_CASHFLOW_FROM_INVESTING_ACTIVITIES','NET_CASHFLOW_FROM_FINANCING_ACTIVITIES')))
ORDER BY p.end_date,s.statement_type,i.item_code,i.id
""",
                (r, n) ->
                    new SourcePoint(
                        "FSI:" + r.getString(1),
                        r.getString(2),
                        r.getString(3),
                        r.getBigDecimal(4),
                        r.getString(5).trim(),
                        r.getDate(6).toLocalDate(),
                        r.getDate(7).toLocalDate(),
                        r.getString(8),
                        r.getString(9),
                        r.getString(10)),
                company,
                request.securityId(),
                start,
                request.asOfDate(),
                cutoff,
                cutoff));
    var limitations = new ArrayList<String>();
    var issues = new ArrayList<String>();
    limitations.add(
        "Published dates missing: ingestion time is used conservatively; this is not a verified"
            + " point-in-time backtest dataset.");
    if (points.stream().anyMatch(p -> "UNKNOWN".equals(p.scope())))
      limitations.add("Report scope UNKNOWN: consolidation cannot be assumed.");
    limitations.add(
        "Profit period values are provider-reported; cumulative/standalone basis is not verified."
            + " Do not annualize or claim TTM.");
    for (var target : request.targets()) {
      var history = points.stream().filter(p -> p.code().equals(target.name())).toList();
      if (history.stream().map(SourcePoint::periodEnd).distinct().count() < 4)
        issues.add("INSUFFICIENT_HISTORY:" + target);
      var keys = new HashSet<String>();
      if (history.stream().anyMatch(p -> !keys.add(p.periodEnd().toString())))
        issues.add("AMBIGUOUS_REPORT_SCOPE:" + target);
      if (!history.isEmpty()
          && history.getLast().periodEnd().isBefore(request.asOfDate().minusDays(450)))
        issues.add("STALE_FINANCIAL:" + target);
    }
    int financialPeriods = (int) points.stream().map(SourcePoint::periodEnd).distinct().count();
    var market =
        db.query(
            """
SELECT id,close_price,(price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d,created_at::text
FROM market_prices WHERE security_id=? AND is_canonical AND interval_code='1d'
    AND close_price>0 AND price_timestamp<? AND created_at<?
ORDER BY price_timestamp DESC,id DESC LIMIT 60
""",
            (r, n) ->
                new SourcePoint(
                    "PRICE:" + r.getLong(1),
                    "MARKET",
                    "CLOSE",
                    r.getBigDecimal(2),
                    "SOURCE_PRICE_UNIT_UNVERIFIED",
                    r.getDate(3).toLocalDate(),
                    r.getDate(3).toLocalDate(),
                    "DAILY",
                    "UNADJUSTED",
                    r.getString(4)),
            request.securityId(),
            cutoff,
            cutoff);
    if (market.stream().map(SourcePoint::periodEnd).distinct().count() != market.size())
      issues.add("AMBIGUOUS_DAILY_PRICES");
    if (market.size() < 20) limitations.add("Fewer than 20 daily market observations.");
    if (!market.isEmpty()
        && market.getFirst().periodEnd().isBefore(request.asOfDate().minusDays(15)))
      limitations.add("Market data older than 15 calendar days.");
    limitations.add(
        "Prices are unadjusted and source unit is not verified: no PE/PB or calibrated price"
            + " forecast is calculated.");
    points.addAll(market);
    points.addAll(
        db.query(
            """
            SELECT m.id,d.code,m.value,d.unit,m.as_of_date,m.created_at::text
            FROM financial_metrics m JOIN metric_definitions d ON d.id=m.metric_definition_id
            WHERE m.company_id=? AND m.security_id=? AND m.is_canonical AND m.quality_status='VALID'
                AND m.value IS NOT NULL AND m.as_of_date BETWEEN ? AND ? AND m.created_at<?
            ORDER BY m.as_of_date DESC,d.code,m.id LIMIT 100
            """,
            (r, n) ->
                new SourcePoint(
                    "METRIC:" + r.getString(1),
                    "METRIC",
                    r.getString(2),
                    r.getBigDecimal(3),
                    r.getString(4),
                    r.getDate(5).toLocalDate(),
                    r.getDate(5).toLocalDate(),
                    "AS_OF",
                    "CANONICAL",
                    r.getString(6)),
            company,
            request.securityId(),
            start,
            request.asOfDate(),
            cutoff));
    var macro =
        db.query(
            """
SELECT o.id,s.code,o.value,s.unit,o.observation_date,s.frequency,o.created_at::text
FROM macro_observations o JOIN macro_series s ON s.id=o.macro_series_id
WHERE s.is_active AND o.value IS NOT NULL AND o.observation_date BETWEEN ? AND ? AND o.created_at<?
ORDER BY o.observation_date DESC,s.code,o.id DESC LIMIT 120
""",
            (r, n) ->
                new SourcePoint(
                    "MACRO:" + r.getLong(1),
                    "MACRO",
                    r.getString(2),
                    r.getBigDecimal(3),
                    r.getString(4),
                    r.getDate(5).toLocalDate(),
                    r.getDate(5).toLocalDate(),
                    r.getString(6),
                    "OBSERVATION_DATE_NOT_RELEASE_DATE",
                    r.getString(7)),
            request.asOfDate().minusMonths(12),
            request.asOfDate(),
            cutoff);
    if (macro.isEmpty()) {
      limitations.add("MACRO_NOT_AVAILABLE: no numeric macro assumptions may be invented.");
      if (request.requireMacro()) issues.add("MACRO_REQUIRED_BUT_MISSING");
    } else
      limitations.add(
          "Macro release/vintage timestamps are not modeled; ingestion time is only an availability"
              + " proxy.");
    points.addAll(macro);
    var metrics = calculator.calculate(points);
    var root = json.mapper().createObjectNode();
    LocalDate date =
        YearMonth.of(
                request.asOfDate().getYear(),
                ((request.asOfDate().getMonthValue() - 1) / 3 + 1) * 3)
            .plusMonths((request.horizonQuarters() - 1L) * 3)
            .atEndOfMonth();
    if (!date.isAfter(request.asOfDate()))
      date = date.plusMonths(3).withDayOfMonth(1).plusMonths(1).minusDays(1);
    root.put("schema_version", "financial.forecast.input.v1")
        .put("company_id", company.toString())
        .put("security_id", request.securityId().toString())
        .put("symbol", security.getFirst().get("symbol").toString())
        .put("company_name", security.getFirst().get("legal_name").toString())
        .put("industry", Objects.toString(security.getFirst().get("industry_name"), "UNKNOWN"))
        .put("as_of_date", request.asOfDate().toString())
        .put("forecast_period_end", date.toString())
        .put("forecast_basis", "SCENARIO_RELATIVE_TO_LAST_REPORTED_COMPARABLE_VALUE_NOT_TTM");
    root.set(
        "targets",
        json.mapper().valueToTree(request.targets().stream().map(Enum::name).sorted().toList()));
    root.set("points", json.mapper().valueToTree(points));
    root.set("derived_metrics", json.mapper().valueToTree(metrics));
    root.put("macro_available", !macro.isEmpty());
    root.set("limitations", json.mapper().valueToTree(limitations));
    var coverage =
        new Coverage(financialPeriods, market.size(), macro.size(), List.copyOf(limitations));
    return new Context(
        company,
        request,
        date,
        List.copyOf(points),
        metrics,
        coverage,
        List.copyOf(issues),
        root,
        hashes.sha256(json.canonical(root).toString()));
  }

  public List<SecurityOption> securities(int limit) {
    return db.query(
        "SELECT s.id,s.company_id,s.symbol,c.legal_name FROM securities s JOIN companies c ON"
            + " c.id=s.company_id ORDER BY s.symbol LIMIT ?",
        (r, n) ->
            new SecurityOption(
                r.getObject(1, UUID.class),
                r.getObject(2, UUID.class),
                r.getString(3),
                r.getString(4)),
        Math.max(1, Math.min(500, limit)));
  }

  public List<StoredMetric> storedMetrics(UUID security, int limit) {
    return db.query(
        "SELECT"
            + " m.id,d.code,m.value,d.unit,m.as_of_date,m.is_derived,m.is_canonical,m.quality_status,m.calculation_version,m.input_snapshot::text"
            + " FROM financial_metrics m JOIN metric_definitions d ON d.id=m.metric_definition_id"
            + " WHERE m.security_id=? ORDER BY m.created_at DESC,m.id LIMIT ?",
        (r, n) ->
            new StoredMetric(
                r.getObject(1, UUID.class),
                r.getString(2),
                r.getBigDecimal(3),
                r.getString(4),
                r.getDate(5).toLocalDate(),
                r.getBoolean(6),
                r.getBoolean(7),
                r.getString(8),
                r.getString(9),
                r.getString(10) == null ? null : json.read(r.getString(10))),
        security,
        Math.max(1, Math.min(200, limit)));
  }

  @Transactional
  public MetricCalculation recalculate(ForecastRequest request) {
    db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        "financial-ratios:" + request.securityId());
    var context = build(request);
    int inserted = 0;
    for (var metric : context.metrics()) {
      var sourceId = UUID.fromString(metric.sourcePointIds().getFirst().substring(4));
      var source =
          db.queryForList(
                  "SELECT s.financial_period_id,s.data_source_id,s.data_version_id,s.raw_payload_id"
                      + " FROM financial_statement_items i JOIN financial_statements s ON"
                      + " s.id=i.financial_statement_id WHERE i.id=?",
                  sourceId)
              .getFirst();
      var definition =
          db.queryForList("SELECT id,unit FROM metric_definitions WHERE code=?", metric.code());
      if (definition.isEmpty() || !metric.unit().equals(definition.getFirst().get("unit")))
        continue;
      var provenance =
          (com.fasterxml.jackson.databind.node.ObjectNode) json.mapper().valueToTree(metric);
      provenance.set(
          "source_points",
          json.mapper()
              .valueToTree(
                  context.points().stream()
                      .filter(p -> metric.sourcePointIds().contains(p.id()))
                      .toList()));
      provenance.put("as_of_date", request.asOfDate().toString());
      String key =
          hashes.sha256(
              context.company()
                  + ":"
                  + request.securityId()
                  + ":"
                  + request.asOfDate()
                  + ":"
                  + json.canonical(json.mapper().valueToTree(metric)));
      inserted +=
          db.update(
              """
INSERT INTO financial_metrics(id,company_id,security_id,financial_period_id,metric_definition_id,as_of_date,
    value,data_source_id,raw_payload_id,data_version_id,is_derived,is_canonical,calculation_version,quality_status,input_snapshot,calculation_key)
VALUES (?,?,?,?,?,?,?,?,?,?,true,false,'balance-ratios-v1','WARNING',?::jsonb,?)
ON CONFLICT (calculation_key) WHERE calculation_key IS NOT NULL DO NOTHING
""",
              UUID.randomUUID(),
              context.company(),
              request.securityId(),
              source.get("financial_period_id"),
              definition.getFirst().get("id"),
              request.asOfDate(),
              metric.value(),
              source.get("data_source_id"),
              source.get("raw_payload_id"),
              source.get("data_version_id"),
              provenance.toString(),
              key);
    }
    return new MetricCalculation(
        request, context.metrics(), context.coverage().limitations(), inserted);
  }
}
