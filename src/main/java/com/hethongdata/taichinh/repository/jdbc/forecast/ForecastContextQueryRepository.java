package com.hethongdata.taichinh.repository.jdbc.forecast;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ForecastContextQueryRepository {
  private final JdbcTemplate db;

  public ForecastContextQueryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> findSecurityCompanyById(UUID securityId) {
    return db.queryForList(
        """
        SELECT s.company_id,s.symbol,c.legal_name,c.industry_name
        FROM securities s JOIN companies c ON c.id=s.company_id WHERE s.id=?
        """,
        securityId);
  }

  public List<Map<String, Object>> findStatementItemSourcePoints(
      UUID companyId, UUID securityId, LocalDate start, LocalDate asOfDate, Timestamp cutoff, int recentDays, boolean strict) {
    return db.queryForList(
        """
        SELECT i.id,s.statement_type,i.item_code,i.value*s.unit_scale AS amount,s.currency,p.start_date,p.end_date,
            p.period_type,s.report_scope,t.available_at::text AS available_at,t.availability_source,
            s.period_basis,s.metadata_provenance::text
        FROM financial_statement_items i JOIN financial_statements s ON s.id=i.financial_statement_id
        JOIN financial_periods p ON p.id=s.financial_period_id
        CROSS JOIN (SELECT ?::int AS recent_days) cfg
        CROSS JOIN LATERAL (
          SELECT CASE WHEN s.published_at IS NOT NULL THEN s.published_at
                      WHEN (s.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - p.end_date <= cfg.recent_days THEN s.created_at
                      ELSE p.end_date::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh' END AS available_at,
                 CASE WHEN s.published_at IS NOT NULL THEN 'PUBLISHED_AT'
                      WHEN (s.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - p.end_date <= cfg.recent_days THEN 'CREATED_AT'
                      ELSE 'PERIOD_END' END AS availability_source
        ) t
        WHERE s.company_id=? AND s.security_id=? AND s.is_current AND s.is_canonical
            AND s.currency='VND' AND s.unit_scale>0 AND i.unit='VND' AND i.value IS NOT NULL
            AND p.end_date BETWEEN ? AND ? AND t.available_at<?
            AND (NOT ? OR (s.published_at IS NOT NULL AND s.created_at<? AND i.created_at<?))
            AND p.period_type IN ('Q1','Q2','Q3','Q4')
            AND ((s.statement_type='INCOME_STATEMENT' AND i.item_code IN ('NET_PROFIT_AFTER_TAX','PRETAX_PROFIT'))
                OR (s.statement_type='BALANCE_SHEET' AND i.item_code IN ('TOTAL_ASSETS','OWNERS_EQUITY',
                    'LIABILITIES','CURRENT_ASSETS','SHORT_TERM_LIABILITIES','CASH_AND_CASH_EQUIVALENTS'))
                OR (s.statement_type='CASH_FLOW' AND i.item_code IN ('NET_CASHFLOW_FROM_OPERATING_ACTIVITIES',
                    'NET_CASHFLOW_FROM_INVESTING_ACTIVITIES','NET_CASHFLOW_FROM_FINANCING_ACTIVITIES')))
        ORDER BY p.end_date,s.statement_type,i.item_code,i.id
        """,
        recentDays, companyId, securityId, start, asOfDate, cutoff, strict, cutoff, cutoff);
  }

  public List<Map<String, Object>> findMarketPriceSourcePoints(UUID securityId, Timestamp cutoff) {
    return db.queryForList(
        """
        SELECT id,close_price,(price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d,created_at::text,raw_payload_id,data_version_id
        FROM market_prices WHERE security_id=? AND is_canonical AND interval_code='1d'
            AND close_price>0 AND price_timestamp<? AND created_at<?
        ORDER BY price_timestamp DESC,id DESC LIMIT 60
        """,
        securityId, cutoff, cutoff);
  }

  public List<Map<String, Object>> findFinancialMetricSourcePoints(
      UUID companyId, UUID securityId, LocalDate startDate, LocalDate endDate, Timestamp cutoff) {
    return db.queryForList(
        """
        SELECT m.id,d.code,m.value,d.unit,m.as_of_date,m.created_at::text
        FROM financial_metrics m JOIN metric_definitions d ON d.id=m.metric_definition_id
        WHERE m.company_id=? AND m.security_id=? AND m.is_canonical AND m.quality_status='VALID'
            AND m.value IS NOT NULL AND m.as_of_date BETWEEN ? AND ? AND m.created_at<?
        ORDER BY m.as_of_date DESC,d.code,m.id LIMIT 100
        """,
        companyId, securityId, startDate, endDate, cutoff);
  }

  public List<Map<String, Object>> findMacroObservationSourcePoints(
      LocalDate startDate, LocalDate endDate, Timestamp cutoff) {
    return db.queryForList(
        """
        SELECT o.id,s.code,o.value,s.unit,o.observation_date,s.frequency,o.created_at::text
        FROM macro_observations o JOIN macro_series s ON s.id=o.macro_series_id
        WHERE s.is_active AND s.country_code='VNM' AND o.value IS NOT NULL AND o.observation_date BETWEEN ? AND ? AND o.created_at<?
        ORDER BY o.observation_date DESC,s.code,o.id DESC LIMIT 120
        """,
        startDate, endDate, cutoff);
  }

  public List<Map<String, Object>> findSecuritiesOptions(int limit) {
    return db.queryForList(
        """
        SELECT s.id,s.company_id,s.symbol,c.legal_name FROM securities s JOIN companies c ON
        c.id=s.company_id ORDER BY s.symbol LIMIT ?
        """,
        limit);
  }

  public List<Map<String, Object>> findStoredMetrics(UUID securityId, int limit) {
    return db.queryForList(
        """
        SELECT m.id,d.code,m.value,d.unit,m.as_of_date,m.is_derived,m.is_canonical,m.quality_status,m.calculation_version,m.input_snapshot::text
        FROM financial_metrics m JOIN metric_definitions d ON d.id=m.metric_definition_id
        WHERE m.security_id=? ORDER BY m.created_at DESC,m.id LIMIT ?
        """,
        securityId, limit);
  }

  public void lockFinancialRatios(UUID securityId) {
    db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        "financial-ratios:" + securityId);
  }

  public List<Map<String, Object>> findStatementSourceByItemId(UUID itemId) {
    return db.queryForList(
        """
        SELECT s.financial_period_id,s.data_source_id,s.data_version_id,s.raw_payload_id
        FROM financial_statement_items i JOIN financial_statements s ON s.id=i.financial_statement_id
        WHERE i.id=?
        """,
        itemId);
  }

  public List<Map<String, Object>> findMetricDefinitionByCode(String code) {
    return db.queryForList("SELECT id,unit FROM metric_definitions WHERE code=?", code);
  }

  public int insertCalculatedMetric(
      UUID companyId,
      UUID securityId,
      UUID financialPeriodId,
      Long metricDefinitionId,
      LocalDate asOfDate,
      BigDecimal value,
      Long dataSourceId,
      UUID rawPayloadId,
      UUID dataVersionId,
      String inputSnapshotJson,
      String calculationKey) {
    return db.update(
        """
        INSERT INTO financial_metrics(id,company_id,security_id,financial_period_id,metric_definition_id,as_of_date,
            value,data_source_id,raw_payload_id,data_version_id,is_derived,is_canonical,calculation_version,quality_status,input_snapshot,calculation_key)
        VALUES (?,?,?,?,?,?,?,?,?,?,true,false,'balance-ratios-v1','WARNING',?::jsonb,?)
        ON CONFLICT (calculation_key) WHERE calculation_key IS NOT NULL DO NOTHING
        """,
        UUID.randomUUID(), companyId, securityId, financialPeriodId, metricDefinitionId, asOfDate,
        value, dataSourceId, rawPayloadId, dataVersionId, inputSnapshotJson, calculationKey);
  }

  public List<Map<String, Object>> findMacroSeriesCoverage(LocalDate fromDate, LocalDate toDate, Timestamp cutoff) {
    return db.queryForList(
        """
        SELECT s.code,s.frequency,max(o.observation_date) latest_date,
            count(o.id) FILTER (WHERE o.observation_date>=?) window_count
        FROM macro_series s LEFT JOIN macro_observations o ON o.macro_series_id=s.id
            AND o.value IS NOT NULL AND o.observation_date<=? AND o.created_at<?
        WHERE s.is_active AND s.country_code='VNM'
        GROUP BY s.id,s.code,s.frequency ORDER BY s.code
        """,
        fromDate, toDate, cutoff);
  }

  public List<Map<String, Object>> findActivatedMarketPriceDataVersion(UUID versionId) {
    return db.queryForList(
        """
        SELECT ingestion_run_id FROM data_versions WHERE id=? AND status='ACTIVATED' AND data_domain='MARKET_PRICE'
        """,
        versionId);
  }

  public List<Map<String, Object>> findRawPayloadWithDataSource(UUID rawId) {
    return db.queryForList(
        """
        SELECT r.payload::text AS body,r.entity_type,r.source_symbol,r.fetched_at,r.ingestion_run_id,d.provider
        FROM raw_payloads r JOIN data_sources d ON d.id=r.data_source_id WHERE r.id=?
        """,
        rawId);
  }
}
