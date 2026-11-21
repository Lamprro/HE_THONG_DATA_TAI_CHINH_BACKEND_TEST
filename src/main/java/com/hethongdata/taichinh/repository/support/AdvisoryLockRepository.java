package com.hethongdata.taichinh.repository.support;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

@Repository
public class AdvisoryLockRepository {
  private final DataSource dataSource;

  public AdvisoryLockRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Connection openConnection() throws SQLException {
    return dataSource.getConnection();
  }

  public boolean tryLock(Connection connection, String key) throws SQLException {
    try (var query = connection.prepareStatement("select pg_try_advisory_lock(hashtext(?))")) {
      query.setString(1, key);
      try (var result = query.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  public void unlock(Connection connection, String key) throws SQLException {
    try (var query = connection.prepareStatement("select pg_advisory_unlock(hashtext(?))")) {
      query.setString(1, key);
      query.execute();
    }
  }
}
