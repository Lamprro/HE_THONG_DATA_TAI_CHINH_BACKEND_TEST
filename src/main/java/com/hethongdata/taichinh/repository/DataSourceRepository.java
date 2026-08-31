package com.hethongdata.taichinh.repository;

import com.hethongdata.taichinh.domain.ingestion.DataSourceConfig;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DataSourceRepository {

    private final JdbcClient jdbcClient;

    public DataSourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<DataSourceConfig> findActiveByProvider(String provider) {
        return jdbcClient.sql("""
                        SELECT id, code, name, provider, base_url, is_active
                        FROM data_sources
                        WHERE is_active = TRUE
                          AND (UPPER(code) = UPPER(:provider) OR UPPER(provider) = UPPER(:provider))
                        ORDER BY CASE WHEN UPPER(code) = UPPER(:provider) THEN 0 ELSE 1 END, priority, id
                        LIMIT 1
                        """)
                .param("provider", provider)
                .query((rs, rowNum) -> new DataSourceConfig(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("provider"),
                        rs.getString("base_url"),
                        rs.getBoolean("is_active")))
                .optional();
    }
}
