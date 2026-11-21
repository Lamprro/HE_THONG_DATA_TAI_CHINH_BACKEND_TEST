package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Official disclosure refresh/coverage, reviewed field-value bindings and audited reconciliation evidence.
 * Bindings never cover an entire statement based on its ticker/quarter alone; a reconciliation residual
 * explains a difference and does not certify a quarterly label.
 */
@Service
public class FinancialDataQualityService {
 private final IngestionService ingest;private final RawPayloadJpaRepository raws;
 private final DataSourceJpaRepository sources;private final FinancialDisclosureDocumentStore documents;
 private final JdbcTemplate jdbc;private final ObjectMapper json;
 public FinancialDataQualityService(IngestionService ingest,RawPayloadJpaRepository raws,DataSourceJpaRepository sources,FinancialDisclosureDocumentStore documents,JdbcTemplate jdbc,ObjectMapper json){this.ingest=ingest;this.raws=raws;this.sources=sources;this.documents=documents;this.jdbc=jdbc;this.json=json;}
 public Map<String,Object> refresh(String symbol,Integer year) {
  return refresh(symbol,year,1);
 }
 public Map<String,Object> refresh(String symbol,Integer year,int page) {
  var official=OfficialFinancialSources.forSymbol(symbol);
  if((year!=null&&(year<2000||year>2099))||page<1||page>100||(!Set.of("HPG","HSG").contains(symbol)&&page!=1))throw new IllegalArgumentException("Invalid year or page");
  var source=sources.findByCodeIgnoreCase(official.code()).orElseThrow(()->new IllegalStateException("Apply financial metadata migration first"));
  if(!official.provider().equals(source.getProvider())||!official.documentOrigin().equals(source.getBaseUrl())||!source.isActive())throw new IllegalStateException("Official source identity mismatch");
  var parameters=new HashMap<String,String>();parameters.put("page",String.valueOf(page));
  if(year!=null)parameters.put("year",year.toString());
  var req=new ManualIngestionRequest();req.setOperation(ExternalOperation.FINANCIAL_DISCLOSURES);req.setProvider(official.provider());req.setSymbol(symbol);req.setDataSourceCode(official.code());req.setParameters(parameters);
  var response=ingest.ingest(req);var raw=raws.findById(response.getRawPayloadId()).orElseThrow();
  var saved=new LinkedHashMap<>(documents.save(raw.getId(),raw.getPayload()));saved.put("ingestionRunId",response.getRunId());saved.put("upstreamStatus",response.getUpstreamStatus());saved.put("nextPage",raw.getPayload().path("next_page"));return saved;
 }
 public Map<String,Object> status(String symbol) {
  if(symbol==null||!symbol.matches("[A-Z0-9._-]{1,20}"))throw new IllegalArgumentException("Invalid symbol");
  var counts=jdbc.queryForMap("""
   SELECT count(*) AS canonical_statements,
    count(*) FILTER(WHERE s.published_at IS NULL) AS missing_publication,
    count(*) FILTER(WHERE s.report_scope IS NULL OR s.report_scope='UNKNOWN') AS missing_scope,
    count(*) FILTER(WHERE COALESCE(s.metadata_provenance#>>'{source,revision_status}','UNKNOWN')='UNKNOWN') AS missing_revision,
    count(*) FILTER(WHERE s.unit_scale IS NULL OR s.currency IS NULL) AS missing_unit
   FROM financial_statements s JOIN securities se ON se.id=s.security_id
   WHERE se.symbol=? AND s.is_current AND s.is_canonical
   """,symbol);
  return Map.of("symbol",symbol,"canonical",counts,"officialDocuments",jdbc.queryForObject("SELECT count(DISTINCT (source_code,document_id)) FROM financial_disclosure_documents WHERE symbol=?",Long.class,symbol),"trainingReadiness","REQUIRES_VALUE_VINTAGE_REVIEW","supportedOfficialIssuers",OfficialFinancialSources.symbols(),"serverTime",java.time.Instant.now().toString());
 }
 public List<Map<String,Object>> coverage() {
  var result=jdbc.queryForList("""
   SELECT se.symbol,count(s.id) AS canonical_statements,
    count(s.id) FILTER(WHERE s.published_at IS NULL) AS missing_publication,
    count(s.id) FILTER(WHERE s.report_scope IS NULL OR s.report_scope='UNKNOWN') AS missing_scope,
    (SELECT count(DISTINCT (d.source_code,d.document_id)) FROM financial_disclosure_documents d WHERE d.symbol=se.symbol) AS official_documents,
    (SELECT count(*) FROM financial_disclosure_facts f WHERE f.security_id=se.id) AS reviewed_facts
   FROM securities se JOIN financial_statements s ON s.security_id=se.id AND s.is_current AND s.is_canonical
   GROUP BY se.id,se.symbol ORDER BY se.symbol
   """);
  result.forEach(row->{row.put("officialAdapterSupported",OfficialFinancialSources.symbols().contains(row.get("symbol")));row.put("trainingStatus","REQUIRES_VALUE_VINTAGE_REVIEW");});
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

  List<UUID> securityIds;
  if (exchange != null && !exchange.isBlank()) {
   securityIds = jdbc.queryForList(
     "SELECT id FROM securities WHERE upper(symbol)=upper(?) AND upper(exchange)=upper(?) ORDER BY CASE WHEN is_active THEN 0 ELSE 1 END, id",
     UUID.class, symbol, exchange.trim());
  } else {
   securityIds = jdbc.queryForList(
     "SELECT id FROM securities WHERE upper(symbol)=upper(?) ORDER BY CASE WHEN is_active THEN 0 ELSE 1 END, id",
     UUID.class, symbol);
  }
  if (securityIds.isEmpty()) throw new IllegalArgumentException("Symbol not found: " + symbol + (exchange != null ? " on " + exchange : ""));
  UUID securityId = securityIds.getFirst();

  if ("PRICE".equals(gapType)) {
   List<LocalDate> calendar = jdbc.queryForList("""
    SELECT DISTINCT (p.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d
    FROM index_prices p
    JOIN market_indices i ON i.id = p.market_index_id
    WHERE i.code = 'VNINDEX' AND p.interval_code = '1d' AND p.is_canonical
      AND (p.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN ? AND ?
    ORDER BY d
    """, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end))
    .stream().map(row -> toLocalDate(row.get("d"))).toList();

   if (calendar.isEmpty()) {
    calendar = jdbc.queryForList("""
     SELECT (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d
     FROM market_prices
     WHERE interval_code = '1d' AND is_canonical
       AND (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN ? AND ?
     GROUP BY (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
     HAVING count(DISTINCT security_id) >= 2
     ORDER BY d
     """, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end))
     .stream().map(row -> toLocalDate(row.get("d"))).toList();
   }

   Set<LocalDate> existing = new HashSet<>(jdbc.queryForList("""
    SELECT DISTINCT (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d
    FROM market_prices
    WHERE security_id = ? AND interval_code = '1d' AND is_canonical
      AND (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN ? AND ?
    ORDER BY d
    """, securityId, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end))
    .stream().map(row -> toLocalDate(row.get("d"))).toList());

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
   String stmtFilter = (statementType != null && !statementType.isBlank()) ? " AND s.statement_type = ?" : "";
   List<Object> stmtParams = new ArrayList<>();
   stmtParams.add(securityId);
   if (statementType != null && !statementType.isBlank()) {
    stmtParams.add(statementType.trim().toUpperCase());
   }

   Set<UUID> presentPeriodIds = new HashSet<>(jdbc.queryForList("""
    SELECT DISTINCT s.financial_period_id
    FROM financial_statements s
    WHERE s.security_id = ? AND s.is_canonical AND s.is_current""" + stmtFilter,
    UUID.class, stmtParams.toArray()));

   List<Map<String, Object>> periodRows = jdbc.queryForList("""
    SELECT p.id, p.fiscal_year, p.period_type, p.end_date
    FROM financial_periods p
    WHERE (p.period_type IN ('Q1','Q2','Q3','Q4') OR p.period_type = 'QUARTER')
      AND p.end_date BETWEEN ? AND ?
    ORDER BY p.end_date
    """, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));

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
   List<Map<String, Object>> metricRows = jdbc.queryForList("""
    SELECT p.id as "periodId", p.fiscal_year as "fiscalYear", p.period_type as "periodType",
           p.end_date::text as "endDate", 'MISSING_FINANCIAL_METRICS' as "reason"
    FROM financial_periods p
    WHERE (p.period_type IN ('Q1','Q2','Q3','Q4') OR p.period_type = 'QUARTER')
      AND p.end_date BETWEEN ? AND ?
      AND NOT EXISTS (
        SELECT 1 FROM financial_metrics m
        WHERE m.security_id = ? AND (m.financial_period_id = p.id OR m.as_of_date = p.end_date)
      )
    ORDER BY p.end_date
    """, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end), securityId);

   int totalPeriods = jdbc.queryForObject("""
    SELECT count(*) FROM financial_periods p
    WHERE (p.period_type IN ('Q1','Q2','Q3','Q4') OR p.period_type = 'QUARTER')
      AND p.end_date BETWEEN ? AND ?
    """, Integer.class, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));

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

 private static LocalDate toLocalDate(Object obj) {
  if (obj instanceof java.sql.Date d) return d.toLocalDate();
  if (obj instanceof LocalDate ld) return ld;
  return LocalDate.parse(obj.toString());
 }

    // ---- Reviewed field-value bindings ----
    @Transactional
    public Map<String,Object> reconcileBindings(String symbol) {
        validateSymbol(symbol);
        int inserted = jdbc.update("""
            INSERT INTO financial_value_bindings(item_id,fact_id,document_id,canonical_value,
                document_value,document_sha256,comparison,source_available_at,usable_from,evidence)
            SELECT i.id,f.id,d.id,i.value,f.value,f.source_sha256,
                CASE WHEN i.value=f.value THEN 'EXACT_VALUE_MATCH' ELSE 'VALUE_CONFLICT' END,
                GREATEST(f.available_at,d.available_at),
                GREATEST(f.available_at,d.available_at,d.observed_at,f.created_at),
                jsonb_build_object('method','REVIEWED_FACT_DOCUMENT_JOIN','sourcePage',f.source_page,
                    'sourceDocumentRawId',f.source_document_raw_id,'sourceListingRawId',f.source_listing_raw_id,
                    'factValidation',f.validation_evidence,'documentRecordSha256',d.record_sha256,
                    'historicalVintage','ARCHIVED_RECONSTRUCTION','statementMetadataModified',false)
            FROM financial_disclosure_facts f
            JOIN securities se ON se.id=f.security_id
            JOIN (SELECT DISTINCT ON(source_code,document_id) * FROM financial_disclosure_documents ORDER BY source_code,document_id,observed_at DESC,parser_version DESC,id DESC) d ON d.symbol=se.symbol
              AND d.metadata->>'document_url'=f.source_document_url
              AND d.period_end>=f.period_end AND d.published_on=f.published_on
              AND d.report_scope=f.report_scope
            JOIN raw_payloads raw ON raw.id=f.source_document_raw_id
              AND raw.payload->>'source_sha256'=f.source_sha256
              AND raw.payload->>'schema_version'='financial.disclosure.binary.v1'
            JOIN financial_statements s ON s.security_id=f.security_id AND s.is_current AND s.is_canonical
              AND s.statement_type='INCOME_STATEMENT'
            JOIN financial_periods p ON p.id=s.financial_period_id AND p.end_date=f.period_end
              AND p.period_type IN ('Q1','Q2','Q3','Q4')
            JOIN financial_statement_items i ON i.financial_statement_id=s.id AND i.item_code=f.target_code
            WHERE se.symbol=? AND f.report_scope IN ('CONSOLIDATED','SEPARATE')
              AND f.period_basis='STANDALONE_QUARTER' AND f.currency='VND' AND f.unit_scale=1
              AND f.eligibility='RESEARCH_ONLY'
            ON CONFLICT DO NOTHING
            """, symbol);
        return Map.of("symbol",symbol,"insertedBindings",inserted,"vendorStatementsModified",0,
                "historicalTrainingStatus","ARCHIVED_RESEARCH_ONLY");
    }

    public List<Map<String,Object>> listBindings(String symbol, java.time.Instant asOf, int limit) {
        validateSymbol(symbol);
        if (limit<1 || limit>500) throw new IllegalArgumentException("Limit must be 1..500");
        // Both exact and conflict rows are returned for review. This is not an automatic training export.
        return jdbc.queryForList("""
            SELECT id,item_id,fact_id,document_id,period_end,item_code,canonical_value,document_value,
                current_value,comparison,report_scope,period_basis,currency,unit_scale,source_page,
                report_version_id,revision_status,document_url,document_sha256,source_available_at,
                usable_from,stale_binding,historical_training_status,
                (NOT stale_binding AND comparison='EXACT_VALUE_MATCH' AND usable_from<=?::timestamptz)
                    AS observed_value_available
            FROM vw_financial_value_bindings WHERE symbol=?
            ORDER BY period_end DESC,source_available_at DESC,id DESC LIMIT ?
            """,asOf.toString(),symbol,limit);
    }

    private static void validateSymbol(String symbol) {
        if(symbol==null || !symbol.matches("[A-Z0-9._-]{1,20}")) throw new IllegalArgumentException("Invalid symbol");
    }

    // ---- Audited reconciliation evidence ----
    private static void require(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }

    @Transactional
    public Map<String,Object> saveReconciliationEvidence(JsonNode request) throws Exception {
        UUID itemId=UUID.fromString(request.path("itemId").asText());
        var item=jdbc.queryForMap("""
            SELECT i.value,i.item_code,s.security_id,se.symbol,p.end_date FROM financial_statement_items i
            JOIN financial_statements s ON s.id=i.financial_statement_id
            JOIN securities se ON se.id=s.security_id JOIN financial_periods p ON p.id=s.financial_period_id
            WHERE i.id=? AND s.is_current AND s.is_canonical
            """,itemId);
        require(item.get("item_code").equals(request.path("target").asText()),"REVIEW_TARGET");
        String method=request.path("method").asText();boolean difference=method.equals("YTD_DIFFERENCE");
        require(difference||method.equals("DIRECT_DOCUMENT"),"REVIEW_METHOD");
        JsonNode operands=request.path("operands");
        require(operands.isArray()&&operands.size()==(difference?2:1),"REVIEW_OPERANDS");
        require("ASSISTANT_VISUAL_REVIEW".equals(request.path("reviewMethod").asText()),"REVIEW_METHOD_NOT_HUMAN_CERTIFIED");
        BigDecimal result=BigDecimal.ZERO;Instant usable=Instant.EPOCH;
        LocalDate end=((java.sql.Date)item.get("end_date")).toLocalDate();
        String scope=null;
        for(int n=0;n<operands.size();n++) {
            var o=operands.get(n);long documentId=o.path("documentId").asLong();
            var doc=jdbc.queryForMap("SELECT symbol,report_scope,period_end,available_at,observed_at,metadata::text AS metadata FROM financial_disclosure_documents WHERE id=?",documentId);
            require(item.get("symbol").equals(doc.get("symbol")),"REVIEW_ISSUER");
            require(Set.of("CONSOLIDATED","SEPARATE").contains(doc.get("report_scope")),"REVIEW_SCOPE");
            if(scope==null)scope=doc.get("report_scope").toString();else require(scope.equals(doc.get("report_scope")),"REVIEW_SCOPE_CONFLICT");
            require(LocalDate.parse(o.path("periodEnd").asText()).equals(n==0?end:end.minusMonths(3).withDayOfMonth(1).plusMonths(1).minusDays(1)),"REVIEW_PERIOD");
            require(!difference||LocalDate.parse(o.path("periodStart").asText()).equals(LocalDate.of(end.getYear(),1,1)),"REVIEW_YTD_START");
            require(!difference||end.getMonthValue()>3,"REVIEW_Q1_DIFFERENCE");
            require(!LocalDate.parse(o.path("periodEnd").asText()).isAfter(((java.sql.Date)doc.get("period_end")).toLocalDate()),"REVIEW_DOCUMENT_PERIOD");
            require("VND".equals(o.path("currency").asText())&&o.path("unitScale").asInt()==1,"REVIEW_UNIT");
            require(o.path("page").asInt()>0&&o.path("page").asInt()<=1000&&!o.path("column").asText().isBlank(),"REVIEW_LOCATION");
            require(o.path("value").isNumber()&&o.path("parentValue").isNumber()&&o.path("minorityValue").isNumber(),"REVIEW_NUMBERS");
            BigDecimal value=o.path("value").decimalValue();
            require(value.compareTo(o.path("parentValue").decimalValue().add(o.path("minorityValue").decimalValue()))==0,"REVIEW_ACCOUNTING_IDENTITY");
            require("NET_PROFIT_AFTER_TAX".equals(request.path("target").asText()),"REVIEW_UNSUPPORTED_NUMERIC_CHECK");
            var raw=jdbc.queryForMap("""
                SELECT r.payload::text AS payload,r.source_symbol,ds.code,run.status,run.request_url
                FROM raw_payloads r JOIN data_sources ds ON ds.id=r.data_source_id
                JOIN ingestion_runs run ON run.id=r.ingestion_run_id WHERE r.id=?
                """,UUID.fromString(o.path("rawId").asText()));
            require(item.get("symbol").equals(raw.get("source_symbol"))&&"SUCCESS".equals(raw.get("status")),"REVIEW_RAW_SOURCE");
            var payload=json.readTree(raw.get("payload").toString());
            require("financial.disclosure.binary.v1".equals(payload.path("schema_version").asText()),"REVIEW_PDF_ENVELOPE");
            byte[] bytes=Base64.getDecoder().decode(payload.path("content_base64").asText());
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            require(sha.equals(o.path("sha256").asText())&&sha.equals(payload.path("source_sha256").asText())&&bytes.length==payload.path("source_bytes").asInt(),"REVIEW_PDF_HASH");
            var source=OfficialFinancialSources.forSymbol(item.get("symbol").toString());
            require(source.code().equals(raw.get("code")),"REVIEW_PROVIDER");
            String path=java.net.URI.create(json.readTree(doc.get("metadata").toString()).path("document_url").asText()).getPath();
            require(java.net.URI.create(raw.get("request_url").toString()).getPath().equals("/api/v1/proxy/"+source.provider()+path),"REVIEW_DOCUMENT_RAW_LINK");
            for(String time:List.of("available_at","observed_at")) {
                Instant instant=((java.sql.Timestamp)doc.get(time)).toInstant();if(instant.isAfter(usable))usable=instant;
            }
            result=n==0?value:result.subtract(value);
        }
        // These are reviewed archival documents, so never backdate eligibility to their claimed publication.
        if(Instant.now().isAfter(usable))usable=Instant.now();
        String encoded=request.toString(),hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        BigDecimal canonical=(BigDecimal)item.get("value");
        int inserted=jdbc.update("""
            INSERT INTO financial_reconciliation_evidence(item_id,canonical_value,evidence_value,method,evidence_sha256,evidence,usable_from)
            VALUES (?,?,?,?,?,?::jsonb,?::timestamptz) ON CONFLICT DO NOTHING
            """,itemId,canonical,result,method,hash,encoded,usable.toString());
        return Map.of("inserted",inserted,"canonicalValue",canonical,"evidenceValue",result,"difference",canonical.subtract(result),"trainingEligible",false);
    }
    public List<Map<String,Object>> listReconciliationEvidence(String symbol) {
        require(symbol!=null&&symbol.matches("[A-Z0-9._-]{1,20}"),"INVALID_SYMBOL");
        return jdbc.queryForList("""
            SELECT r.id,p.end_date,i.item_code,r.canonical_value,r.evidence_value,r.canonical_value-r.evidence_value AS difference,
                r.method,r.evidence,r.usable_from,r.training_eligible,(i.value IS DISTINCT FROM r.canonical_value) AS stale_review
            FROM financial_reconciliation_evidence r JOIN financial_statement_items i ON i.id=r.item_id
            JOIN financial_statements s ON s.id=i.financial_statement_id JOIN securities se ON se.id=s.security_id
            JOIN financial_periods p ON p.id=s.financial_period_id WHERE se.symbol=? ORDER BY r.id DESC LIMIT 200
            """,symbol);
    }
}
