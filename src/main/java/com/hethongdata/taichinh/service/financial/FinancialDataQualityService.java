package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.repository.jdbc.financial.FinancialQualityQueryRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates whether financial statement/price data are usable for point-in-time downstream processing.
 * Bindings never cover an entire statement based on its ticker/quarter alone; a reconciliation residual
 * explains a difference and does not certify a quarterly label.
 */
@Service
public class FinancialDataQualityService {
 private final IngestionService ingest;
 private final RawPayloadJpaRepository raws;
 private final DataSourceJpaRepository sources;
 private final FinancialDisclosureDocumentStore documents;
 private final FinancialQualityQueryRepository qualityRepo;
 private final ObjectMapper json;

 public FinancialDataQualityService(
     IngestionService ingest,
     RawPayloadJpaRepository raws,
     DataSourceJpaRepository sources,
     FinancialDisclosureDocumentStore documents,
     FinancialQualityQueryRepository qualityRepo,
     ObjectMapper json) {
  this.ingest = ingest;
  this.raws = raws;
  this.sources = sources;
  this.documents = documents;
  this.qualityRepo = qualityRepo;
  this.json = json;
 }

 public Map<String, Object> refresh(String symbol, Integer year) {
  return refresh(symbol, year, 1);
 }

 public Map<String, Object> refresh(String symbol, Integer year, int page) {
  var official = OfficialFinancialSources.forSymbol(symbol);
  if ((year != null && (year < 2000 || year > 2099)) || page < 1 || page > 100 || (!Set.of("HPG", "HSG").contains(symbol) && page != 1))
   throw new IllegalArgumentException("Invalid year or page");
  var source = sources.findByCodeIgnoreCase(official.code()).orElseThrow(() -> new IllegalStateException("Apply financial metadata migration first"));
  if (!official.provider().equals(source.getProvider()) || !official.documentOrigin().equals(source.getBaseUrl()) || !source.isActive())
   throw new IllegalStateException("Official source identity mismatch");
  var parameters = new HashMap<String, String>();
  parameters.put("page", String.valueOf(page));
  if (year != null) parameters.put("year", year.toString());
  var req = new ManualIngestionRequest();
  req.setOperation(ExternalOperation.FINANCIAL_DISCLOSURES);
  req.setProvider(official.provider());
  req.setSymbol(symbol);
  req.setDataSourceCode(official.code());
  req.setParameters(parameters);
  var response = ingest.ingest(req);
  var raw = raws.findById(response.getRawPayloadId()).orElseThrow();
  var saved = new LinkedHashMap<>(documents.save(raw.getId(), raw.getPayload()));
  saved.put("ingestionRunId", response.getRunId());
  saved.put("upstreamStatus", response.getUpstreamStatus());
  saved.put("nextPage", raw.getPayload().path("next_page"));
  return saved;
 }

 public Map<String, Object> status(String symbol) {
  if (symbol == null || !symbol.matches("[A-Z0-9._-]{1,20}"))
   throw new IllegalArgumentException("Invalid symbol");
  var counts = qualityRepo.findStatementStatusCounts(symbol);
  return Map.of(
      "symbol", symbol,
      "canonical", counts,
      "officialDocuments", qualityRepo.countOfficialDocuments(symbol),
      "trainingReadiness", "REQUIRES_VALUE_VINTAGE_REVIEW",
      "supportedOfficialIssuers", OfficialFinancialSources.symbols(),
      "serverTime", java.time.Instant.now().toString());
 }

 public List<Map<String, Object>> coverage() {
  var result = qualityRepo.findCoverage();
  result.forEach(row -> {
   row.put("officialAdapterSupported", OfficialFinancialSources.symbols().contains(row.get("symbol")));
   row.put("trainingStatus", "REQUIRES_VALUE_VINTAGE_REVIEW");
  });
  return result;
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

 // ---- Reviewed field-value bindings ----
 @Transactional
 public Map<String, Object> reconcileBindings(String symbol) {
  validateSymbol(symbol);
  int inserted = qualityRepo.insertBindings(symbol);
  return Map.of(
      "symbol", symbol,
      "insertedBindings", inserted,
      "vendorStatementsModified", 0,
      "historicalTrainingStatus", "ARCHIVED_RESEARCH_ONLY");
 }

 public List<Map<String, Object>> listBindings(String symbol, java.time.Instant asOf, int limit) {
  validateSymbol(symbol);
  if (limit < 1 || limit > 500) throw new IllegalArgumentException("Limit must be 1..500");
  return qualityRepo.listBindings(symbol, asOf.toString(), limit);
 }

 private static void validateSymbol(String symbol) {
  if (symbol == null || !symbol.matches("[A-Z0-9._-]{1,20}")) throw new IllegalArgumentException("Invalid symbol");
 }

 private static void require(boolean condition, String message) {
  if (!condition) throw new IllegalArgumentException(message);
 }

 @Transactional
 public Map<String, Object> saveReconciliationEvidence(JsonNode request) throws Exception {
  UUID itemId = UUID.fromString(request.path("itemId").asText());
  var item = qualityRepo.findStatementItemForReconciliation(itemId);
  require(item.get("item_code").equals(request.path("target").asText()), "REVIEW_TARGET");
  String method = request.path("method").asText();
  boolean difference = method.equals("YTD_DIFFERENCE");
  require(difference || method.equals("DIRECT_DOCUMENT"), "REVIEW_METHOD");
  JsonNode operands = request.path("operands");
  require(operands.isArray() && operands.size() == (difference ? 2 : 1), "REVIEW_OPERANDS");
  require("ASSISTANT_VISUAL_REVIEW".equals(request.path("reviewMethod").asText()), "REVIEW_METHOD_NOT_HUMAN_CERTIFIED");
  BigDecimal result = BigDecimal.ZERO;
  Instant usable = Instant.EPOCH;
  LocalDate end = ((java.sql.Date) item.get("end_date")).toLocalDate();
  String scope = null;
  for (int n = 0; n < operands.size(); n++) {
   var o = operands.get(n);
   long documentId = o.path("documentId").asLong();
   var doc = qualityRepo.findDisclosureDocumentById(documentId);
   require(item.get("symbol").equals(doc.get("symbol")), "REVIEW_ISSUER");
   require(Set.of("CONSOLIDATED", "SEPARATE").contains(doc.get("report_scope")), "REVIEW_SCOPE");
   if (scope == null) scope = doc.get("report_scope").toString();
   else require(scope.equals(doc.get("report_scope")), "REVIEW_SCOPE_CONFLICT");
   require(LocalDate.parse(o.path("periodEnd").asText()).equals(n == 0 ? end : end.minusMonths(3).withDayOfMonth(1).plusMonths(1).minusDays(1)), "REVIEW_PERIOD");
   require(!difference || LocalDate.parse(o.path("periodStart").asText()).equals(LocalDate.of(end.getYear(), 1, 1)), "REVIEW_YTD_START");
   require(!difference || end.getMonthValue() > 3, "REVIEW_Q1_DIFFERENCE");
   require(!LocalDate.parse(o.path("periodEnd").asText()).isAfter(((java.sql.Date) doc.get("period_end")).toLocalDate()), "REVIEW_DOCUMENT_PERIOD");
   require("VND".equals(o.path("currency").asText()) && o.path("unitScale").asInt() == 1, "REVIEW_UNIT");
   require(o.path("page").asInt() > 0 && o.path("page").asInt() <= 1000 && !o.path("column").asText().isBlank(), "REVIEW_LOCATION");
   require(o.path("value").isNumber() && o.path("parentValue").isNumber() && o.path("minorityValue").isNumber(), "REVIEW_NUMBERS");
   BigDecimal value = o.path("value").decimalValue();
   require(value.compareTo(o.path("parentValue").decimalValue().add(o.path("minorityValue").decimalValue())) == 0, "REVIEW_ACCOUNTING_IDENTITY");
   require("NET_PROFIT_AFTER_TAX".equals(request.path("target").asText()), "REVIEW_UNSUPPORTED_NUMERIC_CHECK");
   var raw = qualityRepo.findRawPayloadById(UUID.fromString(o.path("rawId").asText()));
   require(item.get("symbol").equals(raw.get("source_symbol")) && "SUCCESS".equals(raw.get("status")), "REVIEW_RAW_SOURCE");
   var payload = json.readTree(raw.get("payload").toString());
   require("financial.disclosure.binary.v1".equals(payload.path("schema_version").asText()), "REVIEW_PDF_ENVELOPE");
   byte[] bytes = Base64.getDecoder().decode(payload.path("content_base64").asText());
   String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   require(sha.equals(o.path("sha256").asText()) && sha.equals(payload.path("source_sha256").asText()) && bytes.length == payload.path("source_bytes").asInt(), "REVIEW_PDF_HASH");
   var source = OfficialFinancialSources.forSymbol(item.get("symbol").toString());
   require(source.code().equals(raw.get("code")), "REVIEW_PROVIDER");
   String path = java.net.URI.create(json.readTree(doc.get("metadata").toString()).path("document_url").asText()).getPath();
   require(java.net.URI.create(raw.get("request_url").toString()).getPath().equals("/api/v1/proxy/" + source.provider() + path), "REVIEW_DOCUMENT_RAW_LINK");
   for (String time : List.of("available_at", "observed_at")) {
    Instant instant = ((java.sql.Timestamp) doc.get(time)).toInstant();
    if (instant.isAfter(usable)) usable = instant;
   }
   result = n == 0 ? value : result.subtract(value);
  }
  if (Instant.now().isAfter(usable)) usable = Instant.now();
  String encoded = request.toString();
  String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  BigDecimal canonical = (BigDecimal) item.get("value");
  int inserted = qualityRepo.insertReconciliationEvidence(itemId, canonical, result, method, hash, encoded, usable.toString());
  return Map.of(
      "inserted", inserted,
      "canonicalValue", canonical,
      "evidenceValue", result,
      "difference", canonical.subtract(result),
      "trainingEligible", false);
 }

 public List<Map<String, Object>> listReconciliationEvidence(String symbol) {
  require(symbol != null && symbol.matches("[A-Z0-9._-]{1,20}"), "INVALID_SYMBOL");
  return qualityRepo.listReconciliationEvidence(symbol);
 }
}
