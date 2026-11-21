package com.hethongdata.taichinh.repository.jdbc.market;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrainingQualityQueryRepository {
  private final JdbcTemplate db;

  public TrainingQualityQueryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public UUID findSecurityIdForUpdate(String symbol) {
    return db.queryForObject("SELECT id FROM securities WHERE symbol=? FOR UPDATE", UUID.class, symbol);
  }

  public List<Map<String, Object>> findRawPayloadsForSecurityPrices(UUID securityId) {
    return db.queryForList(
        """
        SELECT r.id,r.payload::text body,r.entity_type,r.fetched_at FROM raw_payloads r
        WHERE EXISTS(SELECT 1 FROM market_prices m WHERE m.raw_payload_id=r.id AND m.security_id=? AND m.interval_code='1d')
        """,
        securityId);
  }

  public int[] batchUpdateMarketPrices(List<Object[]> args) {
    return db.batchUpdate(
        """
        UPDATE market_prices SET price_basis='UNADJUSTED',price_metadata=?::jsonb,first_seen_at=coalesce(first_seen_at,?)
        WHERE security_id=? AND raw_payload_id=? AND price_timestamp=? AND interval_code='1d'
        AND open_price IS NOT DISTINCT FROM ?::numeric AND high_price IS NOT DISTINCT FROM ?::numeric
        AND low_price IS NOT DISTINCT FROM ?::numeric AND close_price=? AND adjusted_close=?
        """,
        args);
  }

  public void demoteCanonicalMarketPrices(UUID securityId) {
    db.update(
        "UPDATE market_prices SET is_canonical=false WHERE security_id=? AND interval_code='1d' AND is_canonical",
        securityId);
  }

  public void promoteCanonicalMarketPrices(UUID securityId) {
    db.update(
        """
        WITH ranked AS (SELECT m.id,row_number() OVER(PARTITION BY m.price_timestamp ORDER BY
         (m.price_basis='UNADJUSTED' AND m.adjusted_close IS NOT NULL) DESC,d.is_official DESC,d.priority,d.id,m.id) n
         FROM market_prices m JOIN data_sources d ON d.id=m.data_source_id JOIN data_versions v ON v.id=m.data_version_id
         WHERE m.security_id=? AND m.interval_code='1d' AND v.status='ACTIVATED')
        UPDATE market_prices m SET is_canonical=true FROM ranked r WHERE m.id=r.id AND r.n=1
        """,
        securityId);
  }

  public List<Map<String, Object>> findLegacyFinancialStatements(UUID securityId) {
    return db.queryForList(
        """
        SELECT s.id,s.statement_type,p.end_date,r.payload::text body,r.id raw_id FROM financial_statements s
        JOIN financial_periods p ON p.id=s.financial_period_id JOIN raw_payloads r ON r.id=s.raw_payload_id
        WHERE s.security_id=? AND coalesce(s.metadata_provenance->>'contract','')<>'financial.metadata.v1'
        """,
        securityId);
  }

  public int updateFinancialStatementMetadata(String periodBasis, String evidenceJson, UUID statementId) {
    return db.update(
        "UPDATE financial_statements SET period_basis=?,metadata_provenance=?::jsonb,is_restated=NULL WHERE id=?",
        periodBasis, evidenceJson, statementId);
  }

  public List<Map<String, Object>> findCoverageSecurities() {
    return db.queryForList(
        """
        SELECT se.symbol,count(m.id) FILTER(WHERE m.is_canonical) daily_prices,
        min(m.price_timestamp) FILTER(WHERE m.is_canonical) first_price,max(m.price_timestamp) FILTER(WHERE m.is_canonical) last_price,
        count(m.id) FILTER(WHERE m.is_canonical AND m.adjusted_close IS NULL) missing_adjusted_close,
        count(m.id) FILTER(WHERE m.is_canonical AND m.price_basis='UNKNOWN') unknown_price_basis,
        count(m.id) FILTER(WHERE m.is_canonical AND m.foreign_raw_payload_id IS NOT NULL) foreign_source_days
        FROM securities se LEFT JOIN market_prices m ON m.security_id=se.id AND m.interval_code='1d' GROUP BY se.id ORDER BY se.symbol
        """);
  }

  public List<Map<String, Object>> findCoverageStatements() {
    return db.queryForList(
        """
        SELECT statement_type,report_scope,period_basis,count(*) count,count(*) FILTER(WHERE published_at IS NULL) missing_published_at,
        count(*) FILTER(WHERE currency IS NULL OR unit_scale IS NULL) missing_units FROM financial_statements
        WHERE is_current AND is_canonical GROUP BY statement_type,report_scope,period_basis
        """);
  }

  public Long countStrictPointInTimeEligibleItems() {
    return db.queryForObject(
        "SELECT count(*) FROM vw_training_statement_inputs_v1 WHERE strict_point_in_time_eligible",
        Long.class);
  }
}
