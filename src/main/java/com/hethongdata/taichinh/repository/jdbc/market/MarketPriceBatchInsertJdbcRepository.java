package com.hethongdata.taichinh.repository.jdbc.market;

import com.hethongdata.taichinh.entity.MarketPriceEntity;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketPriceBatchInsertJdbcRepository {
  private final JdbcTemplate jdbc;

  public MarketPriceBatchInsertJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int batchInsertMarketPrices(List<MarketPriceEntity> created) {
    jdbc.batchUpdate(
        """
        INSERT INTO market_prices (security_id,price_timestamp,interval_code,open_price,high_price,low_price,
            close_price,adjusted_close,reference_price,ceiling_price,floor_price,volume,trading_value,
            foreign_buy_volume,foreign_sell_volume,data_source_id,raw_payload_id,data_version_id,
            is_canonical,created_at,updated_at,price_basis,first_seen_at,price_metadata,currency,price_unit)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)
        """,
        created,
        100,
        (statement, p) -> {
          statement.setObject(1, p.getSecurityId());
          statement.setObject(2, java.sql.Timestamp.from(p.getPriceTimestamp()));
          statement.setObject(3, p.getIntervalCode());
          statement.setObject(4, p.getOpenPrice());
          statement.setObject(5, p.getHighPrice());
          statement.setObject(6, p.getLowPrice());
          statement.setObject(7, p.getClosePrice());
          statement.setObject(8, p.getAdjustedClose());
          statement.setObject(9, p.getReferencePrice());
          statement.setObject(10, p.getCeilingPrice());
          statement.setObject(11, p.getFloorPrice());
          statement.setObject(12, p.getVolume());
          statement.setObject(13, p.getTradingValue());
          statement.setObject(14, p.getForeignBuyVolume());
          statement.setObject(15, p.getForeignSellVolume());
          statement.setObject(16, p.getDataSourceId());
          statement.setObject(17, p.getRawPayloadId());
          statement.setObject(18, p.getDataVersionId());
          statement.setObject(19, p.getIsCanonical());
          statement.setObject(20, java.sql.Timestamp.from(p.getCreatedAt()));
          statement.setObject(21, java.sql.Timestamp.from(p.getUpdatedAt()));
          statement.setObject(22, p.getPriceBasis());
          statement.setObject(23, java.sql.Timestamp.from(p.getFirstSeenAt()));
          statement.setObject(24, p.getPriceMetadata().toString());
          statement.setString(25, p.getCurrency());
          statement.setString(26, p.getPriceUnit());
        });
    return created.size();
  }
}
