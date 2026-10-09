package com.hethongdata.taichinh.service.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.service.llm.LlmJson;
import com.hethongdata.taichinh.service.market.MarketPricePayloadParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reuses the production price parser to prove each stored close against its activated raw batch.
 */
@Component
public class ForecastMarketEvidence {
  public record RawPrices(UUID ingestionRun, Map<LocalDate, BigDecimal> closes) {}

  private final JdbcTemplate db;
  private final LlmJson json;
  private final MarketPricePayloadParser parser;

  public ForecastMarketEvidence(JdbcTemplate db, LlmJson json, MarketPricePayloadParser parser) {
    this.db = db;
    this.json = json;
    this.parser = parser;
  }

  public boolean verified(
      String symbol,
      UUID rawId,
      UUID versionId,
      LocalDate date,
      BigDecimal close,
      Map<UUID, RawPrices> cache) {
    if (rawId == null || versionId == null) return false;
    try {
      if (!cache.containsKey(rawId)) cache.put(rawId, load(symbol, rawId));
      var raw = cache.get(rawId);
      if (raw == null) return false;
      var version =
          db.queryForList(
              "SELECT ingestion_run_id FROM data_versions WHERE id=? AND status='ACTIVATED' AND"
                  + " data_domain='MARKET_PRICE'",
              versionId);
      return !version.isEmpty()
          && raw.ingestionRun().equals(version.getFirst().get("ingestion_run_id"))
          && raw.closes().containsKey(date)
          && raw.closes().get(date).compareTo(close) == 0;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private RawPrices load(String symbol, UUID rawId) {
    var rows =
        db.queryForList(
            "SELECT r.payload::text AS"
                + " body,r.entity_type,r.source_symbol,r.fetched_at,r.ingestion_run_id,d.provider"
                + " FROM raw_payloads r JOIN data_sources d ON d.id=r.data_source_id WHERE r.id=?",
            rawId);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    String provider = Objects.toString(row.get("provider"), "");
    JsonNode body = json.read(row.get("body").toString());
    if (!Set.of("vnstock", "vndirect", "cafef").contains(provider)
        || !provider.equals(body.path("provider").asText())
        || !"OHLCV".equals(row.get("entity_type"))) return null;
    // A normalized contract must explicitly assert its unit; legacy providers use the existing
    // production parser's reviewed VND multiplier, verified by matching the stored value below.
    if ("market_price.v1".equals(body.path("schema_version").asText())
        && !"VND".equals(body.path("price_unit").asText())) return null;
    Instant fetched = ((java.sql.Timestamp) row.get("fetched_at")).toInstant();
    var batch =
        parser.parse(body, "OHLCV", Objects.toString(row.get("source_symbol"), symbol), fetched);
    if (!symbol.equals(batch.symbol())) return null;
    var closes = new HashMap<LocalDate, BigDecimal>();
    for (var bar : batch.rows()) {
      LocalDate day = bar.timestamp().atZone(MarketPricePayloadParser.VIETNAM_ZONE).toLocalDate();
      if (closes.putIfAbsent(day, bar.close()) != null) return null;
    }
    return new RawPrices((UUID) row.get("ingestion_run_id"), Map.copyOf(closes));
  }
}
