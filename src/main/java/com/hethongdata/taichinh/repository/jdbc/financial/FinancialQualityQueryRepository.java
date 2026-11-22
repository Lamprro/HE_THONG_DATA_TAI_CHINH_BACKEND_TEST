package com.hethongdata.taichinh.repository.jdbc.financial;

import java.sql.Date;
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

  public List<Map<String, Object>> findCoverage() {
    return db.queryForList(
        """
        SELECT se.symbol,count(s.id) AS canonical_statements,
         count(s.id) FILTER(WHERE s.published_at IS NULL) AS missing_publication,
         count(s.id) FILTER(WHERE s.report_scope IS NULL OR s.report_scope='UNKNOWN') AS missing_scope
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

  private static LocalDate toLocalDate(Object obj) {
    if (obj instanceof java.sql.Date d) return d.toLocalDate();
    if (obj instanceof LocalDate ld) return ld;
    return LocalDate.parse(obj.toString());
  }
}
