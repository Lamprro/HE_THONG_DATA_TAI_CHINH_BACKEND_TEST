package com.hethongdata.taichinh.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.forecast.*;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import com.hethongdata.taichinh.repository.jdbc.forecast.ForecastContextQueryRepository;
import com.hethongdata.taichinh.service.financial.FinancialRatioCalculator;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.LlmJson;
import com.hethongdata.taichinh.service.market.MarketPricePayloadParser;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastContextService {
  static LocalDate targetDate(LocalDate asOf, int horizon) {
    YearMonth first = YearMonth.of(asOf.getYear(), ((asOf.getMonthValue() - 1) / 3 + 1) * 3);
    if (!first.atEndOfMonth().isAfter(asOf)) first = first.plusMonths(3);
    return first.plusMonths((horizon - 1L) * 3).atEndOfMonth();
  }

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

  private final ForecastContextQueryRepository repo;
  private final LlmJson json;
  private final ChecksumService hashes;
  private final FinancialRatioCalculator calculator;
  private final MarketPricePayloadParser priceParser;
  private final int yearlyMonths, quarterlyMonths, monthlyMonths, dailyDays;
  private final int recentStatementDays;
  private final boolean strictStatementDates;

  public ForecastContextService(
      ForecastContextQueryRepository repo,
      LlmJson json,
      ChecksumService hashes,
      FinancialRatioCalculator calculator,
      MarketPricePayloadParser priceParser,
      @Value("${financial.forecast.macro.max-age.yearly-months:24}") int yearlyMonths,
      @Value("${financial.forecast.macro.max-age.quarterly-months:9}") int quarterlyMonths,
      @Value("${financial.forecast.macro.max-age.monthly-months:4}") int monthlyMonths,
      @Value("${financial.forecast.macro.max-age.daily-days:15}") int dailyDays,
      @Value("${financial.forecast.statement.recent-days:90}") int recentStatementDays,
      @Value("${financial.forecast.statement.strict-publication:false}") boolean strictStatementDates) {
    if (recentStatementDays < 1) throw new IllegalArgumentException("Statement recent-days must be positive");
    this.recentStatementDays = recentStatementDays;
    this.strictStatementDates = strictStatementDates;
    if (yearlyMonths < 1 || quarterlyMonths < 1 || monthlyMonths < 1 || dailyDays < 1)
      throw new IllegalArgumentException("Macro freshness thresholds must be positive");
    this.repo = repo;
    this.json = json;
    this.hashes = hashes;
    this.calculator = calculator;
    this.priceParser = priceParser;
    this.yearlyMonths = yearlyMonths;
    this.quarterlyMonths = quarterlyMonths;
    this.monthlyMonths = monthlyMonths;
    this.dailyDays = dailyDays;
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
    var security = repo.findSecurityCompanyById(request.securityId());
    if (security.isEmpty()) throw new IllegalArgumentException("Security not found");
    UUID company = (UUID) security.getFirst().get("company_id");
    var cutoff =
        java.sql.Timestamp.from(
            request.asOfDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
    LocalDate start = request.asOfDate().minusYears(5);
    LocalDate targetDate = resolveTargetDate(request.asOfDate(), request.horizonQuarters(), request.forecastPeriodEnd());
    var rawStatementItems = repo.findStatementItemSourcePoints(
        company, request.securityId(), start, request.asOfDate(), cutoff, recentStatementDays, strictStatementDates);
    var points = new ArrayList<SourcePoint>();
    for (var r : rawStatementItems) {
      String id = r.get("id").toString();
      String statementType = (String) r.get("statement_type");
      String itemCode = (String) r.get("item_code");
      BigDecimal amount = (BigDecimal) r.get("amount");
      String currency = ((String) r.get("currency")).trim();
      LocalDate startDate = "YTD".equals(r.get("period_basis"))
          ? ((java.sql.Date) r.get("end_date")).toLocalDate().withDayOfYear(1)
          : ((java.sql.Date) r.get("start_date")).toLocalDate();
      LocalDate endDate = ((java.sql.Date) r.get("end_date")).toLocalDate();
      String periodType = (String) r.get("period_type");
      String reportScope = (String) r.get("report_scope");
      String availableAt = (String) r.get("available_at");
      String periodBasis = (String) r.get("period_basis");
      JsonNode metadataProvenance = r.get("metadata_provenance") == null ? null : json.read((String) r.get("metadata_provenance"));
      var provenance = metadataProvenance != null && metadataProvenance.isObject()
          ? (com.fasterxml.jackson.databind.node.ObjectNode) metadataProvenance.deepCopy()
          : json.mapper().createObjectNode();
      provenance.put("forecastAvailabilitySource", (String) r.get("availability_source"));
      points.add(new SourcePoint(
          "FSI:" + id, statementType, itemCode, amount, currency, startDate, endDate,
          periodType, reportScope, availableAt, periodBasis, provenance));
    }
    var limitations = new ArrayList<String>();
    var issues = new ArrayList<String>();
    if (!strictStatementDates) limitations.add(
        "Statements use current canonical data. Missing publication dates use ingestion time for recent periods"
            + " or fiscal period end for older periods; this is not a point-in-time backtest dataset.");
    if (points.stream().anyMatch(p -> "UNKNOWN".equals(p.scope())))
      limitations.add("Report scope UNKNOWN: consolidation cannot be assumed.");
    if (points.stream().filter(p->"INCOME_STATEMENT".equals(p.domain()))
        .anyMatch(p->"UNKNOWN".equals(p.periodBasis()))) limitations.add(
        "Profit period values are provider-reported; cumulative/standalone basis is not verified."
            + " Do not annualize or claim TTM.");
    for (var target : request.targets()) {
      if (target == ForecastRequest.Target.STOCK_PRICE) continue;
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
    boolean priceRequested = request.targets().contains(ForecastRequest.Target.STOCK_PRICE);
    var rawPrices = new HashMap<UUID, RawPrices>();
    var marketRows = repo.findMarketPriceSourcePoints(request.securityId(), cutoff);
    var market = new ArrayList<SourcePoint>();
    for (var r : marketRows) {
      market.add(new SourcePoint(
          "PRICE:" + r.get("id"),
          "MARKET",
          "CLOSE",
          (BigDecimal) r.get("close_price"),
          priceRequested
                  && verifiedMarketClose(
                      security.getFirst().get("symbol").toString(),
                      (UUID) r.get("raw_payload_id"),
                      (UUID) r.get("data_version_id"),
                      ((java.sql.Date) r.get("d")).toLocalDate(),
                      (BigDecimal) r.get("close_price"),
                      rawPrices)
              ? "VND_PER_SHARE"
              : "SOURCE_PRICE_UNIT_UNVERIFIED",
          ((java.sql.Date) r.get("d")).toLocalDate(),
          ((java.sql.Date) r.get("d")).toLocalDate(),
          "DAILY",
          "UNADJUSTED",
          (String) r.get("created_at")));
    }
    if (market.stream().map(SourcePoint::periodEnd).distinct().count() != market.size())
      issues.add("AMBIGUOUS_DAILY_PRICES");
    if (market.size() < 20) limitations.add("Fewer than 20 daily market observations.");
    if (!market.isEmpty()
        && market.getFirst().periodEnd().isBefore(request.asOfDate().minusDays(15)))
      limitations.add("Market data older than 15 calendar days.");
    if (priceRequested) {
      if (market.size() < 60) issues.add("INSUFFICIENT_PRICE_HISTORY: require 60 daily sessions");
      if (market.stream().anyMatch(p -> !"VND_PER_SHARE".equals(p.unit())))
        issues.add("PRICE_UNIT_OR_RAW_PROVENANCE_UNVERIFIED");
      if (!market.isEmpty()
          && market.getFirst().periodEnd().isBefore(request.asOfDate().minusDays(15)))
        issues.add("STALE_PRICE_BASE");
      limitations.add(
          "Stock price scenario uses unadjusted nominal VND/share close; corporate actions and"
              + " dividends are not forecast. Growth is relative to latest close, not total return"
              + " or fair value. Not backtested/calibrated.");
    }
    limitations.add(
        "Prices are unadjusted; no PE/PB or calibrated price forecast is calculated. Price units"
            + " must be proven against raw for STOCK_PRICE.");
    points.addAll(market);
    var metricRows = repo.findFinancialMetricSourcePoints(company, request.securityId(), start, request.asOfDate(), cutoff);
    for (var r : metricRows) {
      points.add(new SourcePoint(
          "METRIC:" + r.get("id"),
          "METRIC",
          (String) r.get("code"),
          (BigDecimal) r.get("value"),
          (String) r.get("unit"),
          ((java.sql.Date) r.get("as_of_date")).toLocalDate(),
          ((java.sql.Date) r.get("as_of_date")).toLocalDate(),
          "AS_OF",
          "CANONICAL",
          (String) r.get("created_at")));
    }
    var macroRows = repo.findMacroObservationSourcePoints(request.asOfDate().minusMonths(12), request.asOfDate(), cutoff);
    var macro = new ArrayList<SourcePoint>();
    for (var r : macroRows) {
      macro.add(new SourcePoint(
          "MACRO:" + r.get("id"),
          "MACRO",
          (String) r.get("code"),
          (BigDecimal) r.get("value"),
          (String) r.get("unit"),
          ((java.sql.Date) r.get("observation_date")).toLocalDate(),
          ((java.sql.Date) r.get("observation_date")).toLocalDate(),
          (String) r.get("frequency"),
          "OBSERVATION_DATE_NOT_RELEASE_DATE",
          (String) r.get("created_at")));
    }
    if (macro.isEmpty()) {
      limitations.add("MACRO_NOT_AVAILABLE: no numeric macro assumptions may be invented.");
      if (request.requireMacro()) issues.add("MACRO_REQUIRED_BUT_MISSING");
    } else
      limitations.add(
          "Macro release/vintage timestamps are not modeled; ingestion time is only an availability"
              + " proxy.");
    points.addAll(macro);
    var macroCoverage = macroCoverage(request.asOfDate(), cutoff, macro);
    if (macroCoverage.isEmpty()) limitations.add("MACRO_CATALOG_MISSING: no active Vietnam series configured.");
    for (var series : macroCoverage) {
      if (!"AVAILABLE".equals(series.status())) limitations.add("MACRO_"+series.status()+":"+series.code()
          + "; latest observation="+series.latestDate()+"; sent="+series.sentObservationCount());
    }
    var metrics = new ArrayList<>(calculator.calculate(points));
    if (priceRequested
        && issues.stream().noneMatch(i -> i.contains("PRICE") || i.contains("PRICES")))
      metrics.addAll(marketScenarioMetrics(market));
    var root = json.mapper().createObjectNode();
    LocalDate date = targetDate;
    root.put("schema_version", "financial.forecast.input.v2")
        .put("company_id", company.toString())
        .put("security_id", request.securityId().toString())
        .put("symbol", security.getFirst().get("symbol").toString())
        .put("company_name", security.getFirst().get("legal_name").toString())
        .put("industry", Objects.toString(security.getFirst().get("industry_name"), "UNKNOWN"))
        .put("as_of_date", request.asOfDate().toString())
        .put("forecast_period_start", periodStart(date).toString())
        .put("forecast_period_end", date.toString())
        .put("horizon_quarters", request.horizonQuarters())
        .put("horizon_basis", request.forecastPeriodEnd() == null
            ? "END_OF_NTH_CALENDAR_QUARTER_AFTER_AS_OF_QUARTER" : "EXPLICIT_CALENDAR_QUARTER")
        .put("forecast_basis", "SCENARIO_RELATIVE_TO_LAST_REPORTED_COMPARABLE_VALUE_NOT_TTM");
    root.set(
        "targets",
        json.mapper().valueToTree(request.targets().stream().map(Enum::name).sorted().toList()));
    root.set("points", json.mapper().valueToTree(points));
    root.set("derived_metrics", json.mapper().valueToTree(metrics));
    root.put("macro_available", !macro.isEmpty());
    root.set("macro_coverage", json.mapper().valueToTree(macroCoverage));
    root.set("limitations", json.mapper().valueToTree(limitations));
    var coverage =
        new Coverage(financialPeriods, market.size(), macro.size(), List.copyOf(limitations), macroCoverage);
    return new Context(
        company,
        request,
        date,
        List.copyOf(points),
        List.copyOf(metrics),
        coverage,
        List.copyOf(issues),
        root,
        hashes.sha256(json.canonical(root).toString()));
  }

  public List<SecurityOption> securities(int limit) {
    var rows = repo.findSecuritiesOptions(Math.max(1, Math.min(500, limit)));
    return rows.stream().map(r -> new SecurityOption(
        (UUID) r.get("id"),
        (UUID) r.get("company_id"),
        (String) r.get("symbol"),
        (String) r.get("legal_name")
    )).toList();
  }

  public List<StoredMetric> storedMetrics(UUID security, int limit) {
    var rows = repo.findStoredMetrics(security, Math.max(1, Math.min(200, limit)));
    return rows.stream().map(r -> new StoredMetric(
        (UUID) r.get("id"),
        (String) r.get("code"),
        (BigDecimal) r.get("value"),
        (String) r.get("unit"),
        ((java.sql.Date) r.get("as_of_date")).toLocalDate(),
        (Boolean) r.get("is_derived"),
        (Boolean) r.get("is_canonical"),
        (String) r.get("quality_status"),
        (String) r.get("calculation_version"),
        r.get("input_snapshot") == null ? null : json.read((String) r.get("input_snapshot"))
    )).toList();
  }

  @Transactional
  public MetricCalculation recalculate(ForecastRequest request) {
    repo.lockFinancialRatios(request.securityId());
    var context = build(request);
    int inserted = 0;
    for (var metric : context.metrics()) {
      if (metric.sourcePointIds().isEmpty()
          || !metric.sourcePointIds().getFirst().startsWith("FSI:")) continue;
      var sourceId = UUID.fromString(metric.sourcePointIds().getFirst().substring(4));
      var sourceRows = repo.findStatementSourceByItemId(sourceId);
      if (sourceRows.isEmpty()) continue;
      var source = sourceRows.getFirst();
      var definition = repo.findMetricDefinitionByCode(metric.code());
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
          repo.insertCalculatedMetric(
              context.company(),
              request.securityId(),
              (UUID) source.get("financial_period_id"),
              (Long) definition.getFirst().get("id"),
              request.asOfDate(),
              metric.value(),
              (Long) source.get("data_source_id"),
              (UUID) source.get("raw_payload_id"),
              (UUID) source.get("data_version_id"),
              provenance.toString(),
              key);
    }
    return new MetricCalculation(
        request, context.metrics(), context.coverage().limitations(), inserted);
  }

  public static LocalDate periodStart(LocalDate end) {
    return LocalDate.of(end.getYear(), ((end.getMonthValue() - 1) / 3) * 3 + 1, 1);
  }

  public static LocalDate resolveTargetDate(LocalDate asOf, int quarters, LocalDate explicitEnd) {
    LocalDate maximum = endOfFutureQuarter(asOf, 8);
    if (explicitEnd == null) return endOfFutureQuarter(asOf, quarters);
    if (explicitEnd.isBefore(asOf) || explicitEnd.isAfter(maximum)
        || explicitEnd.getMonthValue() % 3 != 0
        || !explicitEnd.equals(YearMonth.from(explicitEnd).atEndOfMonth()))
      throw new IllegalArgumentException("forecastPeriodEnd must be a current/future calendar quarter end within eight quarters");
    return explicitEnd;
  }

  public static LocalDate endOfFutureQuarter(LocalDate asOf, int quarters) {
    if (asOf == null || quarters < 1 || quarters > 8)
      throw new IllegalArgumentException("Choose a date and 1-8 future quarters");
    int quarterEndMonth = ((asOf.getMonthValue() - 1) / 3 + 1) * 3;
    return YearMonth.of(asOf.getYear(), quarterEndMonth).plusMonths(quarters * 3L).atEndOfMonth();
  }

  List<MacroSeriesCoverage> macroCoverage(LocalDate asOf, java.sql.Timestamp cutoff, List<SourcePoint> points) {
    var rows = repo.findMacroSeriesCoverage(asOf.minusMonths(12), asOf, cutoff);
    var list = new ArrayList<MacroSeriesCoverage>();
    for (var r : rows) {
      LocalDate latest = r.get("latest_date") == null ? null : ((java.sql.Date) r.get("latest_date")).toLocalDate();
      String frequency = (String) r.get("frequency");
      String code = (String) r.get("code");
      long count = ((Number) r.get("window_count")).longValue();
      long sent = points.stream().filter(p -> p.code().equals(code)).count();
      LocalDate oldest = switch (frequency) {
        case "YEARLY" -> asOf.minusMonths(yearlyMonths);
        case "QUARTERLY" -> asOf.minusMonths(quarterlyMonths);
        case "MONTHLY" -> asOf.minusMonths(monthlyMonths);
        case "DAILY" -> asOf.minusDays(dailyDays);
        default -> null;
      };
      String status = latest == null ? "MISSING" : oldest == null ? "UNSUPPORTED_FREQUENCY" :
          latest.isBefore(oldest) ? "STALE" : count == 0 ? "OUTSIDE_WINDOW" : sent < count ? "TRUNCATED_CONTEXT" : "AVAILABLE";
      list.add(new MacroSeriesCoverage(
          code, frequency, latest, count, sent, status,
          latest == null, latest != null && oldest != null && latest.isBefore(oldest)));
    }
    return list;
  }

  static List<DerivedMetric> marketScenarioMetrics(List<SourcePoint> market) {
    var history =
        market.stream()
            .filter(p -> "VND_PER_SHARE".equals(p.unit()))
            .sorted(Comparator.comparing(SourcePoint::periodEnd).reversed())
            .toList();
    var result = new ArrayList<DerivedMetric>();
    for (int sessions : List.of(20, 60)) {
      if (history.size() < sessions) continue;
      var trailing = history.subList(0, sessions);
      BigDecimal mean =
          trailing.stream()
              .map(SourcePoint::value)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .divide(BigDecimal.valueOf(sessions), 8, RoundingMode.HALF_UP);
      result.add(
          new DerivedMetric(
              "SMA_" + sessions,
              mean,
              "VND_PER_SHARE",
              "sum(close of last " + sessions + " sessions) / " + sessions,
              trailing.stream().map(SourcePoint::id).toList()));
    }
    if (history.size() > 20) {
      var latest = history.getFirst();
      var previous = history.get(20);
      if (previous.value().signum() > 0)
        result.add(
            new DerivedMetric(
                "PRICE_RETURN_20_SESSIONS",
                latest
                    .value()
                    .divide(previous.value(), 12, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100)),
                "%",
                "(latest close / close 20 sessions earlier - 1) * 100",
                List.of(latest.id(), previous.id())));
    }
    return List.copyOf(result);
  }

  record RawPrices(UUID ingestionRun, Map<LocalDate, BigDecimal> closes) {}

  private boolean verifiedMarketClose(
      String symbol,
      UUID rawId,
      UUID versionId,
      LocalDate date,
      BigDecimal close,
      Map<UUID, RawPrices> cache) {
    if (rawId == null || versionId == null) return false;
    try {
      if (!cache.containsKey(rawId)) cache.put(rawId, loadRawPrices(symbol, rawId));
      var raw = cache.get(rawId);
      if (raw == null) return false;
      var version = repo.findActivatedMarketPriceDataVersion(versionId);
      return !version.isEmpty()
          && raw.ingestionRun().equals(version.getFirst().get("ingestion_run_id"))
          && raw.closes().containsKey(date)
          && raw.closes().get(date).compareTo(close) == 0;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private RawPrices loadRawPrices(String symbol, UUID rawId) {
    var rows = repo.findRawPayloadWithDataSource(rawId);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    String provider = Objects.toString(row.get("provider"), "");
    JsonNode body = json.read(row.get("body").toString());
    if (!Set.of("vnstock", "vndirect", "cafef").contains(provider)
        || !provider.equals(body.path("provider").asText())
        || !"OHLCV".equals(row.get("entity_type"))) return null;
    if ("market_price.v1".equals(body.path("schema_version").asText())
        && !MarketPricePayloadParser.hasVndUnit(body)) return null;
    Instant fetched = ((java.sql.Timestamp) row.get("fetched_at")).toInstant();
    var batch =
        priceParser.parse(body, "OHLCV", Objects.toString(row.get("source_symbol"), symbol), fetched);
    if (!symbol.equals(batch.symbol())) return null;
    var closes = new HashMap<LocalDate, BigDecimal>();
    for (var bar : batch.rows()) {
      LocalDate day = bar.timestamp().atZone(MarketPricePayloadParser.VIETNAM_ZONE).toLocalDate();
      if (closes.putIfAbsent(day, bar.close()) != null) return null;
    }
    return new RawPrices((UUID) row.get("ingestion_run_id"), Map.copyOf(closes));
  }
}
