package com.hethongdata.taichinh.repository.jdbc.macro;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MacroPersistenceJdbcRepository {
  public record PreviousObservation(BigDecimal value, Instant fetchedAt) {}

  private final JdbcTemplate db;

  public MacroPersistenceJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public int countApprovedValidationResults(UUID rawPayloadId, String ruleCode) {
    Integer count = db.queryForObject(
        "SELECT count(*) FROM validation_results WHERE raw_payload_id=? AND rule_code=? AND result_status='PASS'",
        Integer.class, rawPayloadId, ruleCode);
    return count == null ? 0 : count;
  }

  public Long lockDataSourceForUpdate(Long sourceId) {
    return db.queryForObject(
        "SELECT id FROM data_sources WHERE id=? FOR UPDATE", Long.class, sourceId);
  }

  public void insertMacroSeriesIfNotExists(
      UUID id, Long sourceId, String code, String name, String countryCode, String frequency, String unit, String description) {
    db.update(
        """
        INSERT INTO macro_series(id,data_source_id,code,name,country_code,frequency,unit,description,is_active)
        VALUES (?,?,?,?,?,?,?,?,true) ON CONFLICT(data_source_id,code) DO NOTHING
        """,
        id, sourceId, code, name, countryCode, frequency, unit, description);
  }

  public Map<String, Object> lockAndGetMacroSeriesDefinition(Long sourceId, String code) {
    return db.queryForMap(
        """
        SELECT id,country_code,frequency,unit FROM macro_series WHERE data_source_id=? AND code=? FOR UPDATE
        """,
        sourceId, code);
  }

  public List<PreviousObservation> findPreviousObservationForUpdate(UUID seriesId, LocalDate observationDate) {
    return db.query(
        """
        SELECT o.value, r.fetched_at FROM macro_observations o LEFT JOIN raw_payloads r ON r.id=o.raw_payload_id
        WHERE o.macro_series_id=? AND o.observation_date=? FOR UPDATE OF o
        """,
        (r, n) -> new PreviousObservation(
            r.getBigDecimal(1),
            r.getTimestamp(2) == null ? null : r.getTimestamp(2).toInstant()),
        seriesId, observationDate);
  }

  public int insertMacroObservation(
      UUID seriesId, LocalDate observationDate, BigDecimal value, UUID rawPayloadId, UUID dataVersionId) {
    return db.update(
        """
        INSERT INTO macro_observations(macro_series_id,observation_date,value,raw_payload_id,data_version_id)
        VALUES (?,?,?,?,?)
        """,
        seriesId, observationDate, value, rawPayloadId, dataVersionId);
  }

  public int updateMacroObservation(
      UUID seriesId, LocalDate observationDate, BigDecimal value, UUID rawPayloadId, UUID dataVersionId) {
    return db.update(
        """
        UPDATE macro_observations SET value=?,raw_payload_id=?,data_version_id=?,updated_at=now()
        WHERE macro_series_id=? AND observation_date=?
        """,
        value, rawPayloadId, dataVersionId, seriesId, observationDate);
  }

  public LocalDate findEarliestMarketOrStatementDate() {
    var dates = db.query(
        """
        SELECT min(d) FROM (
          SELECT (price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date d FROM market_prices WHERE is_canonical AND interval_code='1d'
          UNION ALL
          SELECT p.start_date FROM financial_periods p JOIN financial_statements s ON s.financial_period_id=p.id WHERE s.is_current AND s.is_canonical
        ) x
        """,
        (r, n) -> r.getDate(1) == null ? null : r.getDate(1).toLocalDate());
    return dates.isEmpty() ? null : dates.getFirst();
  }
}
