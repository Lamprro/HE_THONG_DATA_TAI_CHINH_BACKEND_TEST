package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.repository.jdbc.financial.FinancialQualityQueryRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;

/** Reports coverage and gaps in canonical financial and market data. */
@Service
public class FinancialDataQualityService {
 private final FinancialQualityQueryRepository qualityRepo;

 public FinancialDataQualityService(FinancialQualityQueryRepository qualityRepo) {
  this.qualityRepo = qualityRepo;
 }

 public Map<String, Object> status(String symbol) {
  validateSymbol(symbol);
  var counts = qualityRepo.findStatementStatusCounts(symbol);
  return Map.of(
      "symbol", symbol,
      "canonical", counts,
      "trainingReadiness", "REQUIRES_VALUE_VINTAGE_REVIEW",
      "serverTime", java.time.Instant.now().toString());
 }

 public List<Map<String, Object>> coverage() {
  var coverage = qualityRepo.findCoverage();
  coverage.forEach(row -> row.put("trainingStatus", "REQUIRES_VALUE_VINTAGE_REVIEW"));
  return coverage;
 }

 public Map<String, Object> gaps(String symbol, String type, LocalDate from, LocalDate to) {
  return gaps(symbol, null, type, null, from, to);
 }

 public Map<String, Object> gaps(String symbol, String exchange, String type, LocalDate from, LocalDate to) {
  return gaps(symbol, exchange, type, null, from, to);
 }

 public Map<String, Object> gaps(String symbol, String exchange, String type, String statementType, LocalDate from, LocalDate to) {
  validateSymbol(symbol);
  String gapType = (type == null || type.isBlank()) ? "PRICE" : type.trim().toUpperCase();
  if (gapType.startsWith("STATEMENT:") && (statementType == null || statementType.isBlank())) {
   statementType = gapType.substring("STATEMENT:".length()).trim();
   gapType = "STATEMENT";
  }
  ZoneId vnZone = ZoneId.of("Asia/Ho_Chi_Minh");
  LocalDate end = (to != null) ? to : LocalDate.now(vnZone);
  LocalDate start = (from != null) ? from : end.minusYears(1);

  List<UUID> securityIds = qualityRepo.findSecurityIds(symbol, exchange);
  if (securityIds.isEmpty())
   throw new IllegalArgumentException("Symbol not found: " + symbol + (exchange != null ? " on " + exchange : ""));
  UUID securityId = securityIds.getFirst();

  if ("PRICE".equals(gapType)) {
   List<LocalDate> calendar = qualityRepo.findVNIndexCalendar(start, end);
   if (calendar.isEmpty()) {
    calendar = qualityRepo.findFallbackCalendar(start, end);
   }

   Set<LocalDate> existing = qualityRepo.findExistingPriceDates(securityId, start, end);
   List<String> missingDates = new ArrayList<>();
   List<Map<String, Object>> gapList = new ArrayList<>();
   for (LocalDate d : calendar) {
    if (!existing.contains(d)) {
     missingDates.add(d.toString());
     gapList.add(Map.of("date", d.toString(), "reason", "MISSING_CANONICAL_DAILY_PRICE"));
    }
   }

   Map<String, Object> resp = new LinkedHashMap<>();
   resp.put("symbol", symbol);
   if (exchange != null && !exchange.isBlank()) resp.put("exchange", exchange.trim().toUpperCase());
   resp.put("type", "PRICE");
   resp.put("from", start.toString());
   resp.put("to", end.toString());
   resp.put("totalCalendarDays", calendar.size());
   resp.put("presentDays", existing.size());
   resp.put("missingCount", missingDates.size());
   resp.put("missingDates", missingDates);
   resp.put("gaps", gapList);
   return resp;
  } else if ("STATEMENT".equals(gapType)) {
   Set<UUID> presentPeriodIds = qualityRepo.findPresentPeriodIds(securityId, statementType);
   List<Map<String, Object>> periodRows = qualityRepo.findPeriodRows(start, end);

   List<Map<String, Object>> gapList = new ArrayList<>();
   for (var row : periodRows) {
    UUID pid = (UUID) row.get("id");
    if (!presentPeriodIds.contains(pid)) {
     Map<String, Object> gap = new LinkedHashMap<>();
     gap.put("periodId", pid.toString());
     gap.put("fiscalYear", row.get("fiscal_year"));
     gap.put("periodType", row.get("period_type"));
     gap.put("endDate", row.get("end_date").toString());
     gap.put("reason", statementType != null && !statementType.isBlank()
         ? "MISSING_CANONICAL_" + statementType.trim().toUpperCase()
         : "MISSING_CANONICAL_STATEMENT");
     gapList.add(gap);
    }
   }

   Map<String, Object> resp = new LinkedHashMap<>();
   resp.put("symbol", symbol);
   if (exchange != null && !exchange.isBlank()) resp.put("exchange", exchange.trim().toUpperCase());
   resp.put("type", "STATEMENT");
   if (statementType != null && !statementType.isBlank()) resp.put("statementType", statementType.trim().toUpperCase());
   resp.put("from", start.toString());
   resp.put("to", end.toString());
   resp.put("totalPeriods", periodRows.size());
   resp.put("missingCount", gapList.size());
   resp.put("gaps", gapList);
   return resp;
  } else {
   List<Map<String, Object>> metricRows = qualityRepo.findMissingMetricRows(start, end, securityId);
   int totalPeriods = qualityRepo.countTotalFinancialPeriods(start, end);

   Map<String, Object> resp = new LinkedHashMap<>();
   resp.put("symbol", symbol);
   if (exchange != null && !exchange.isBlank()) resp.put("exchange", exchange.trim().toUpperCase());
   resp.put("type", "METRIC");
   resp.put("from", start.toString());
   resp.put("to", end.toString());
   resp.put("totalPeriods", totalPeriods);
   resp.put("missingCount", metricRows.size());
   resp.put("gaps", metricRows);
   return resp;
  }
 }

 private static void validateSymbol(String symbol) {
  if (symbol == null || !symbol.matches("[A-Z0-9._-]{1,20}")) throw new IllegalArgumentException("Invalid symbol");
 }
}
