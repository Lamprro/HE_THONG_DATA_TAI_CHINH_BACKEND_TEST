package com.hethongdata.taichinh.repository.support;

import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class DatabaseProbeRepository {
  private final DataSource dataSource;

  public DatabaseProbeRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public boolean probe(Duration timeout) throws SQLException {
    try (var connection = dataSource.getConnection();
         var statement = connection.createStatement()) {
      statement.setQueryTimeout((int) Math.max(1, (timeout.toMillis() + 999) / 1000));
      try (var result = statement.executeQuery("SELECT 1")) {
        return result.next() && result.getInt(1) == 1;
      }
    }
  }
}
