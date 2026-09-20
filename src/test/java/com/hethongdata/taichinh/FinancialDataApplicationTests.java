package com.hethongdata.taichinh;

import com.hethongdata.taichinh.entity.MarketPriceEntity;
import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;

@SpringBootTest
class FinancialDataApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MarketPriceJpaRepository marketPrices;

    @Test
    void contextLoads() {
    }

    @Test
    void databaseConnectionWorks() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

        assertThat(result).isEqualTo(1);
    }

    @Test
    void marketPriceSchemaSupportsPipeline() {
        Set<String> columns = Set.copyOf(jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'market_prices'",
                String.class));
        assertThat(columns).contains("id", "security_id", "price_timestamp", "interval_code",
                "data_source_id", "raw_payload_id", "data_version_id", "is_canonical",
                "open_price", "high_price", "low_price", "close_price", "volume",
                "trading_value", "created_at", "updated_at");
    }

    @Test
    @Transactional
    void marketPriceEntityCanInsertAndReadBackInsideRollbackTransaction() {
        UUID securityId = jdbcTemplate.queryForObject(
                "select id from securities order by created_at limit 1", UUID.class);
        Long sourceId = jdbcTemplate.queryForObject(
                "select id from data_sources order by id limit 1", Long.class);
        MarketPriceEntity saved = marketPrices.saveAndFlush(MarketPriceEntity.create(
                securityId, Instant.parse("2100-01-01T00:00:00Z"), "test",
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE,
                null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, sourceId, null, null));
        assertThat(saved.getId()).isNotNull();
        assertThat(marketPrices.findById(saved.getId())).isPresent();
    }
}
