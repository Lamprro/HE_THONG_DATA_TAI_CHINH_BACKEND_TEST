package com.hethongdata.taichinh.repository.jdbc.financial;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialQualityQueryRepository {
  private final JdbcTemplate db;

  public FinancialQualityQueryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> findStatementStatusCounts(String symbol) {
    return db.queryForMap(
        """
        SELECT count(*) AS canonical_statements,
         count(*) FILTER(WHERE s.published_at IS NULL) AS missing_publication,
         count(*) FILTER(WHERE s.report_scope IS NULL OR s.report_scope='UNKNOWN') AS missing_scope,
         count(*) FILTER(WHERE COALESCE(s.metadata_provenance#>>'{source,revision_status}','UNKNOWN')='UNKNOWN') AS missing_revision,
         count(*) FILTER(WHERE s.unit_scale IS NULL OR s.currency IS NULL) AS missing_unit
        FROM financial_statements s JOIN securities se ON se.id=s.security_id
        WHERE se.symbol=? AND s.is_current AND s.is_canonical
        """,
        symbol);
  }

  public Long countOfficialDocuments(String symbol) {
    return db.queryForObject(
        "SELECT count(DISTINCT (source_code,document_id)) FROM financial_disclosure_documents WHERE symbol=?",
        Long.class,
        symbol);
  }

  public List<Map<String, Object>> findCoverage() {
    return db.queryForList(
        """
        SELECT se.symbol,count(s.id) AS canonical_statements,
         count(s.id) FILTER(WHERE s.published_at IS NULL) AS missing_publication,
         count(s.id) FILTER(WHERE s.report_scope IS NULL OR s.report_scope='UNKNOWN') AS missing_scope,
         (SELECT count(DISTINCT (d.source_code,d.document_id)) FROM financial_disclosure_documents d WHERE d.symbol=se.symbol) AS official_documents,
         (SELECT count(*) FROM financial_disclosure_facts f WHERE f.security_id=se.id) AS reviewed_facts
        FROM securities se JOIN financial_statements s ON s.security_id=se.id AND s.is_current AND s.is_canonical
        GROUP BY se.id,se.symbol ORDER BY se.symbol
        """);
  }

  public List<UUID> findSecurityIds(String symbol, String exchange) {
    if (exchange != null && !exchange.isBlank()) {
      return db.queryForList(
          "SELECT id FROM securities WHERE upper(symbol)=upper(?) AND upper(exchange)=upper(?) ORDER BY CASE WHEN is_active THEN 0 ELSE 1 END, id",
          UUID.class, symbol, exchange.trim());
    } else {
      return db.queryForList(
          "SELECT id FROM securities WHERE upper(symbol)=upper(?) ORDER BY CASE WHEN is_active THEN 0 ELSE 1 END, id",
          UUID.class, symbol);
    }
  }

  public List<LocalDate> findVNIndexCalendar(LocalDate start, LocalDate end) {
    return db.queryForList(
        """
        SELECT DISTINCT (p.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d
        FROM index_prices p
        JOIN market_indices i ON i.id = p.market_index_id
        WHERE i.code = 'VNINDEX' AND p.interval_code = '1d' AND p.is_canonical
          AND (p.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN ? AND ?
        ORDER BY d
        """,
        Date.valueOf(start), Date.valueOf(end))
        .stream().map(row -> toLocalDate(row.get("d"))).toList();
  }

  public List<LocalDate> findFallbackCalendar(LocalDate start, LocalDate end) {
    return db.queryForList(
        """
        SELECT (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d
        FROM market_prices
        WHERE interval_code = '1d' AND is_canonical
          AND (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN ? AND ?
        GROUP BY (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
        HAVING count(DISTINCT security_id) >= 2
        ORDER BY d
        """,
        Date.valueOf(start), Date.valueOf(end))
        .stream().map(row -> toLocalDate(row.get("d"))).toList();
  }

  public Set<LocalDate> findExistingPriceDates(UUID securityId, LocalDate start, LocalDate end) {
    return new HashSet<>(db.queryForList(
        """
        SELECT DISTINCT (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d
        FROM market_prices
        WHERE security_id = ? AND interval_code = '1d' AND is_canonical
          AND (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date BETWEEN ? AND ?
        ORDER BY d
        """,
        securityId, Date.valueOf(start), Date.valueOf(end))
        .stream().map(row -> toLocalDate(row.get("d"))).toList());
  }

  public Set<UUID> findPresentPeriodIds(UUID securityId, String statementType) {
    String stmtFilter = (statementType != null && !statementType.isBlank()) ? " AND s.statement_type = ?" : "";
    List<Object> stmtParams = new ArrayList<>();
    stmtParams.add(securityId);
    if (statementType != null && !statementType.isBlank()) {
      stmtParams.add(statementType.trim().toUpperCase());
    }
    return new HashSet<>(db.queryForList(
        """
        SELECT DISTINCT s.financial_period_id
        FROM financial_statements s
        WHERE s.security_id = ? AND s.is_canonical AND s.is_current""" + stmtFilter,
        UUID.class, stmtParams.toArray()));
  }

  public List<Map<String, Object>> findPeriodRows(LocalDate start, LocalDate end) {
    return db.queryForList(
        """
        SELECT p.id, p.fiscal_year, p.period_type, p.end_date
        FROM financial_periods p
        WHERE (p.period_type IN ('Q1','Q2','Q3','Q4') OR p.period_type = 'QUARTER')
          AND p.end_date BETWEEN ? AND ?
        ORDER BY p.end_date
        """,
        Date.valueOf(start), Date.valueOf(end));
  }

  public List<Map<String, Object>> findMissingMetricRows(LocalDate start, LocalDate end, UUID securityId) {
    return db.queryForList(
        """
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
        """,
        Date.valueOf(start), Date.valueOf(end), securityId);
  }

  public int countTotalFinancialPeriods(LocalDate start, LocalDate end) {
    Integer count = db.queryForObject(
        """
        SELECT count(*) FROM financial_periods p
        WHERE (p.period_type IN ('Q1','Q2','Q3','Q4') OR p.period_type = 'QUARTER')
          AND p.end_date BETWEEN ? AND ?
        """,
        Integer.class, Date.valueOf(start), Date.valueOf(end));
    return count == null ? 0 : count;
  }

  public int insertBindings(String symbol) {
    return db.update(
        """
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
        """,
        symbol);
  }

  public List<Map<String, Object>> listBindings(String symbol, String asOfStr, int limit) {
    return db.queryForList(
        """
        SELECT id,item_id,fact_id,document_id,period_end,item_code,canonical_value,document_value,
            current_value,comparison,report_scope,period_basis,currency,unit_scale,source_page,
            report_version_id,revision_status,document_url,document_sha256,source_available_at,
            usable_from,stale_binding,historical_training_status,
            (NOT stale_binding AND comparison='EXACT_VALUE_MATCH' AND usable_from<=?::timestamptz)
                AS observed_value_available
        FROM vw_financial_value_bindings WHERE symbol=?
        ORDER BY period_end DESC,source_available_at DESC,id DESC LIMIT ?
        """,
        asOfStr, symbol, limit);
  }

  public Map<String, Object> findStatementItemForReconciliation(UUID itemId) {
    return db.queryForMap(
        """
        SELECT i.value,i.item_code,s.security_id,se.symbol,p.end_date FROM financial_statement_items i
        JOIN financial_statements s ON s.id=i.financial_statement_id
        JOIN securities se ON se.id=s.security_id JOIN financial_periods p ON p.id=s.financial_period_id
        WHERE i.id=? AND s.is_current AND s.is_canonical
        """,
        itemId);
  }

  public Map<String, Object> findDisclosureDocumentById(long documentId) {
    return db.queryForMap(
        "SELECT symbol,report_scope,period_end,available_at,observed_at,metadata::text AS metadata FROM financial_disclosure_documents WHERE id=?",
        documentId);
  }

  public Map<String, Object> findRawPayloadById(UUID rawId) {
    return db.queryForMap(
        """
        SELECT r.payload::text AS payload,r.source_symbol,ds.code,run.status,run.request_url
        FROM raw_payloads r JOIN data_sources ds ON ds.id=r.data_source_id
        JOIN ingestion_runs run ON run.id=r.ingestion_run_id WHERE r.id=?
        """,
        rawId);
  }

  public int insertReconciliationEvidence(
      UUID itemId,
      BigDecimal canonical,
      BigDecimal result,
      String method,
      String hash,
      String encoded,
      String usableStr) {
    return db.update(
        """
        INSERT INTO financial_reconciliation_evidence(item_id,canonical_value,evidence_value,method,evidence_sha256,evidence,usable_from)
        VALUES (?,?,?,?,?,?::jsonb,?::timestamptz) ON CONFLICT DO NOTHING
        """,
        itemId, canonical, result, method, hash, encoded, usableStr);
  }

  public List<Map<String, Object>> listReconciliationEvidence(String symbol) {
    return db.queryForList(
        """
        SELECT r.id,p.end_date,i.item_code,r.canonical_value,r.evidence_value,r.canonical_value-r.evidence_value AS difference,
            r.method,r.evidence,r.usable_from,r.training_eligible,(i.value IS DISTINCT FROM r.canonical_value) AS stale_review
        FROM financial_reconciliation_evidence r JOIN financial_statement_items i ON i.id=r.item_id
        JOIN financial_statements s ON s.id=i.financial_statement_id JOIN securities se ON se.id=s.security_id
        JOIN financial_periods p ON p.id=s.financial_period_id WHERE se.symbol=? ORDER BY r.id DESC LIMIT 200
        """,
        symbol);
  }

  private static LocalDate toLocalDate(Object obj) {
    if (obj instanceof java.sql.Date d) return d.toLocalDate();
    if (obj instanceof LocalDate ld) return ld;
    return LocalDate.parse(obj.toString());
  }
}
