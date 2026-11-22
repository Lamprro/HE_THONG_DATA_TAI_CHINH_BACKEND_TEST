package com.hethongdata.taichinh.repository.jdbc.macro;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MacroQueryRepository {
  private final JdbcTemplate db;

  public MacroQueryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> vietnamCoverage() {
    return db.queryForList("""
        SELECT s.code,s.name,s.country_code,s.frequency,s.unit,count(o.id) observation_count,min(o.observation_date) first_period_end,max(o.observation_date) last_period_end
        FROM macro_series s LEFT JOIN macro_observations o ON o.macro_series_id=s.id
        WHERE s.country_code='VNM' GROUP BY s.id ORDER BY s.code
        """);
  }
}
