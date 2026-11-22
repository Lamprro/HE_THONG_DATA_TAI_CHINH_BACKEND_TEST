package com.hethongdata.taichinh.repository.jdbc.financial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialMetricJdbcRepository {
  private final JdbcTemplate db;

  public FinancialMetricJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> findSecurityCompanyBySymbol(String symbol) {
    return db.queryForList(
        "SELECT id,company_id FROM securities WHERE upper(symbol)=upper(?)", symbol);
  }

  public void lockCompanyForUpdate(UUID companyId) {
    db.queryForList("SELECT id FROM companies WHERE id=? FOR UPDATE", companyId);
  }

  public int countMetricsByRawId(UUID rawId, String calculationVersion) {
    Integer count = db.queryForObject(
        "SELECT count(*) FROM financial_metrics WHERE raw_payload_id=? AND calculation_version=?",
        Integer.class, rawId, calculationVersion);
    return count == null ? 0 : count;
  }

  public Long findMetricDefinitionIdByCode(String code) {
    return db.queryForObject("SELECT id FROM metric_definitions WHERE code=?", Long.class, code);
  }

  public void batchInsertProviderRatios(List<Object[]> parameters) {
    for (int offset = 0; offset < parameters.size(); offset += 500) {
      db.batchUpdate(
          """
          INSERT INTO financial_metrics(company_id,security_id,metric_definition_id,as_of_date,value,
              data_source_id,raw_payload_id,data_version_id,is_derived,is_canonical,calculation_version,
              quality_status,input_snapshot,calculation_key)
          VALUES (?,?,?,?,?,?,?,?,false,false,'provider-ratio-v1','VALID',?::jsonb,?)
          ON CONFLICT (calculation_key) WHERE calculation_key IS NOT NULL DO NOTHING
          """,
          parameters.subList(offset, Math.min(offset + 500, parameters.size())));
    }
  }

  public void reconcileBatch(UUID company, UUID incomingRaw) {
    db.update(
        """
        UPDATE financial_metrics m SET is_canonical=false
        WHERE m.company_id=? AND m.is_canonical AND EXISTS
         (SELECT 1 FROM financial_metrics incoming WHERE incoming.raw_payload_id=?
          AND incoming.metric_definition_id=m.metric_definition_id AND incoming.as_of_date=m.as_of_date)
        """,
        company, incomingRaw);
    db.update(
        """
        WITH ranked AS (
         SELECT m.id,row_number() OVER(PARTITION BY m.metric_definition_id,m.as_of_date
         ORDER BY d.is_official DESC NULLS LAST,d.priority ASC NULLS LAST,d.id ASC NULLS LAST,
         coalesce(r.fetched_at,m.created_at) DESC,m.created_at DESC,m.id) AS position
         FROM financial_metrics m LEFT JOIN data_sources d ON d.id=m.data_source_id
         LEFT JOIN raw_payloads r ON r.id=m.raw_payload_id
         WHERE m.company_id=? AND m.quality_status='VALID' AND EXISTS
         (SELECT 1 FROM financial_metrics incoming WHERE incoming.raw_payload_id=?
          AND incoming.metric_definition_id=m.metric_definition_id AND incoming.as_of_date=m.as_of_date)
        )
        UPDATE financial_metrics m SET is_canonical=true FROM ranked w WHERE w.position=1 AND w.id=m.id
        """,
        company, incomingRaw);
  }

  public void reconcile(UUID company, Long definition, LocalDate date) {
    var winners = db.queryForList(
        """
        SELECT m.id FROM financial_metrics m LEFT JOIN data_sources d ON d.id=m.data_source_id
        LEFT JOIN raw_payloads r ON r.id=m.raw_payload_id
        WHERE m.company_id=? AND m.metric_definition_id=? AND m.as_of_date=? AND m.quality_status='VALID'
        ORDER BY d.is_official DESC NULLS LAST,d.priority ASC NULLS LAST,d.id ASC NULLS LAST,
            coalesce(r.fetched_at,m.created_at) DESC,m.created_at DESC,m.id LIMIT 1
        """,
        company, definition, date);
    if (winners.isEmpty()) return;
    db.update(
        "UPDATE financial_metrics SET is_canonical=false WHERE company_id=? AND metric_definition_id=? AND as_of_date=? AND is_canonical",
        company, definition, date);
    db.update("UPDATE financial_metrics SET is_canonical=true WHERE id=?", winners.getFirst().get("id"));
  }

  public List<Map<String, Object>> findStatementItemActuals(UUID securityId, LocalDate startDate, LocalDate endDate) {
    return db.queryForList(
        """
        SELECT s.id,s.company_id,s.security_id,s.financial_period_id,s.data_source_id,s.raw_payload_id,
            s.data_version_id,s.statement_type,s.report_scope,i.id AS item_id,i.item_code,i.value*s.unit_scale AS amount,
            s.currency,p.start_date,p.end_date,p.period_type,coalesce(s.published_at,r.fetched_at,s.created_at)::text available
        FROM financial_statements s JOIN financial_periods p ON p.id=s.financial_period_id
        JOIN financial_statement_items i ON i.financial_statement_id=s.id
        JOIN raw_payloads r ON r.id=s.raw_payload_id
        JOIN data_versions v ON v.id=s.data_version_id AND v.ingestion_run_id=r.ingestion_run_id
        WHERE s.security_id=? AND s.is_current AND s.is_canonical AND s.unit_scale>0
            AND v.data_domain='FINANCIAL_STATEMENT' AND v.status='ACTIVATED'
            AND s.currency='VND' AND i.unit='VND' AND i.value IS NOT NULL
            AND s.statement_type IN ('BALANCE_SHEET','INCOME_STATEMENT')
            AND p.end_date BETWEEN ? AND ? AND p.period_type IN ('Q1','Q2','Q3','Q4')
        ORDER BY p.end_date,s.statement_type,s.report_scope,i.item_code,i.id
        """,
        securityId, startDate, endDate);
  }

  public int insertHistoricalActualRatio(
      UUID companyId,
      UUID securityId,
      UUID periodId,
      Long definitionId,
      LocalDate asOfDate,
      BigDecimal value,
      Long dataSourceId,
      UUID rawPayloadId,
      UUID dataVersionId,
      String inputSnapshotJson,
      String calculationKey) {
    return db.update(
        """
        INSERT INTO financial_metrics(company_id,security_id,financial_period_id,metric_definition_id,as_of_date,
            value,data_source_id,raw_payload_id,data_version_id,is_derived,is_canonical,calculation_version,
            quality_status,input_snapshot,calculation_key)
        VALUES (?,?,?,?,?,?,?,?,?,true,false,'historical-actual-ratios-v1','VALID',?::jsonb,?)
        ON CONFLICT (calculation_key) WHERE calculation_key IS NOT NULL DO NOTHING
        """,
        companyId, securityId, periodId, definitionId, asOfDate, value,
        dataSourceId, rawPayloadId, dataVersionId, inputSnapshotJson, calculationKey);
  }

  public List<String> findDistinctSymbolsWithStatements() {
    return db.queryForList(
        """
        SELECT DISTINCT s.symbol FROM securities s
        JOIN financial_statements f ON f.security_id=s.id
        WHERE f.is_current AND f.is_canonical ORDER BY s.symbol
        """,
        String.class);
  }
}
