package com.hethongdata.taichinh.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hethongdata.taichinh.controller.HealthController;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DatabaseHealthServiceTests {
  @Test void readinessQueriesDatabaseAndClosesResources() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    var statement = mock(Statement.class);
    var result = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery("SELECT 1")).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getInt(1)).thenReturn(1);
    var health = new DatabaseHealthService(source, Duration.ofSeconds(2));
    try {
      assertThat(new HealthController(health).health().getStatusCode().value()).isEqualTo(200);
      verify(statement).setQueryTimeout(2);
      verify(connection).close();
      verify(statement).close();
      verify(result).close();
    } finally { health.close(); }
  }

  @Test void databaseFailureReturns503WithoutExposingCredentialsAndLivenessRemainsUp() throws Exception {
    var source = mock(DataSource.class);
    when(source.getConnection()).thenThrow(new SQLException("private connection details"));
    var health = new DatabaseHealthService(source, Duration.ofSeconds(2));
    try {
      var controller = new HealthController(health);
      var response = controller.health();
      assertThat(response.getStatusCode().value()).isEqualTo(503);
      assertThat(response.getBody().toString()).doesNotContain("private connection details");
      assertThat(controller.live().getStatusCode().value()).isEqualTo(200);
      verify(source, times(1)).getConnection();
    } finally { health.close(); }
  }

  @Test void slowPoolIsBoundedAndConcurrentChecksDoNotQueueAnotherConnection() throws Exception {
    var source = mock(DataSource.class);
    var release = new CountDownLatch(1);
    when(source.getConnection()).thenAnswer(i -> {
      // Simulate a JDBC driver that ignores cancellation while acquiring a connection.
      boolean done = false;
      while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) {}
      throw new SQLException("released");
    });
    var health = new DatabaseHealthService(source, Duration.ofMillis(100));
    try {
      long start = System.nanoTime();
      assertThat(health.check().reason()).isEqualTo("TIMEOUT");
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
      assertThat(health.check().reason()).isEqualTo("TIMEOUT");
      verify(source, times(1)).getConnection();
    } finally { release.countDown(); health.close(); }
  }
}
