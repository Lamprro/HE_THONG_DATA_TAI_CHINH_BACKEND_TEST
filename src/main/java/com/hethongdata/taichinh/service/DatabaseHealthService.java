package com.hethongdata.taichinh.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded readiness check using the application's database pool. Never queues extra probes. */
@Service
public class DatabaseHealthService {
  public record DatabaseHealth(String status, String reason) {}

  private final DataSource dataSource;
  private final Duration timeout;
  private Future<DatabaseHealth> inFlight;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "database-readiness");
    thread.setDaemon(true);
    return thread;
  });

  public DatabaseHealthService(DataSource dataSource,
      @Value("${financial.health.database-timeout:2s}") Duration timeout) {
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0)
      throw new IllegalArgumentException("Database health timeout must be positive and at most 10 seconds");
    this.dataSource = dataSource;
    this.timeout = timeout;
  }

  public DatabaseHealth check() {
    Future<DatabaseHealth> probe;
    synchronized (this) {
      try {
        if (inFlight == null || inFlight.isDone()) {
          inFlight = worker.submit(this::probeDatabase);
        }
        probe = inFlight;
      } catch (RejectedExecutionException e) {
        return down("CHECK_UNAVAILABLE");
      }
    }
    try {
      return probe.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // Share the existing probe until JDBC exits; a pool wait may ignore interruption.
      return down("TIMEOUT");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return down("INTERRUPTED");
    } catch (ExecutionException e) {
      return down("CHECK_UNAVAILABLE");
    }
  }

  private DatabaseHealth probeDatabase() {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout((int) Math.max(1, (timeout.toMillis() + 999) / 1000));
      try (var result = statement.executeQuery("SELECT 1")) {
        return result.next() && result.getInt(1) == 1
            ? new DatabaseHealth("UP", null) : down("PROBE_FAILED");
      }
    } catch (Exception e) {
      return down("CONNECTION_UNAVAILABLE");
    }
  }

  private DatabaseHealth down(String reason) {
    return new DatabaseHealth("DOWN", reason);
  }

  @PreDestroy
  public void close() {
    worker.shutdownNow();
  }
}
