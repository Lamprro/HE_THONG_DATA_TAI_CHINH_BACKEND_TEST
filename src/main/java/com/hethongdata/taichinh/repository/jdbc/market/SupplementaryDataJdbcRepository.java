package com.hethongdata.taichinh.repository.jdbc.market;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SupplementaryDataJdbcRepository {
  private final JdbcTemplate db;

  public SupplementaryDataJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public int countPassedValidationResults(UUID rawPayloadId, String ruleCode) {
    Integer count = db.queryForObject(
        "SELECT count(*) FROM validation_results WHERE raw_payload_id=? AND rule_code=? AND result_status='PASS'",
        Integer.class, rawPayloadId, ruleCode);
    return count == null ? 0 : count;
  }

  public UUID findSecurityIdBySymbolForUpdate(String symbol) {
    return db.queryForObject(
        "SELECT id FROM securities WHERE upper(symbol)=upper(?) FOR UPDATE",
        UUID.class, symbol);
  }

  public int countForeignObservationsByRawId(UUID rawPayloadId) {
    Integer count = db.queryForObject(
        "SELECT count(*) FROM security_foreign_observations WHERE raw_payload_id=?",
        Integer.class, rawPayloadId);
    return count == null ? 0 : count;
  }

  public void batchInsertForeignObservations(List<Object[]> args) {
    db.batchUpdate(
        """
        INSERT INTO security_foreign_observations(id,security_id,trading_date,data_source_id,raw_payload_id,data_version_id,buy_volume,sell_volume,source_observed_at,source_record)
        VALUES(?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(raw_payload_id,security_id,trading_date) DO NOTHING
        """,
        args);
  }

  public void reconcileForeignObservations(UUID securityId, UUID rawPayloadId) {
    db.update(
        """
        UPDATE security_foreign_observations f SET is_canonical=false WHERE f.security_id=? AND f.is_canonical
        AND EXISTS(SELECT 1 FROM security_foreign_observations n WHERE n.raw_payload_id=? AND n.trading_date=f.trading_date)
        """,
        securityId, rawPayloadId);
    db.update(
        """
        WITH winners AS (SELECT f.id,row_number() OVER(PARTITION BY f.trading_date ORDER BY d.is_official DESC,d.priority,d.id,f.source_observed_at DESC,f.created_at DESC,f.id) AS position
        FROM security_foreign_observations f JOIN data_sources d ON d.id=f.data_source_id WHERE f.security_id=?
        AND EXISTS(SELECT 1 FROM security_foreign_observations n WHERE n.raw_payload_id=? AND n.trading_date=f.trading_date))
        UPDATE security_foreign_observations f SET is_canonical=true FROM winners w WHERE w.position=1 AND w.id=f.id
        """,
        securityId, rawPayloadId);
  }

  public void syncMarketPriceForeignVolumes(UUID securityId) {
    db.update(
        """
        UPDATE market_prices m SET foreign_buy_volume=f.buy_volume,foreign_sell_volume=f.sell_volume,
         foreign_data_source_id=f.data_source_id,foreign_raw_payload_id=f.raw_payload_id,foreign_data_version_id=f.data_version_id
        FROM security_foreign_observations f WHERE m.security_id=? AND m.security_id=f.security_id AND m.interval_code='1d'
        AND f.is_canonical AND (m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date=f.trading_date
        AND (m.foreign_raw_payload_id IS DISTINCT FROM f.raw_payload_id OR m.foreign_buy_volume IS DISTINCT FROM f.buy_volume OR m.foreign_sell_volume IS DISTINCT FROM f.sell_volume)
        """,
        securityId);
  }

  public int insertReferenceObservation(
      UUID id, UUID securityId, Long dataSourceId, UUID rawPayloadId, UUID dataVersionId,
      Timestamp observedAt, String industryName, BigDecimal outstandingShares,
      LocalDate listedDate, LocalDate delistedDate, String listingStatus, String sourceRecordJson) {
    return db.update(
        """
        INSERT INTO security_reference_observations(id,security_id,data_source_id,raw_payload_id,data_version_id,observed_at,industry_name,outstanding_shares,current_venue_listing_date,delisted_date,listing_status,source_record)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(raw_payload_id) DO NOTHING
        """,
        id, securityId, dataSourceId, rawPayloadId, dataVersionId, observedAt,
        industryName, outstandingShares, listedDate, delistedDate, listingStatus, sourceRecordJson);
  }
}
