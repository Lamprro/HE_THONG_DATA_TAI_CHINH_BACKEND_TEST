package com.hethongdata.taichinh.service;

import com.hethongdata.taichinh.repository.support.DatabaseProbeRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded readiness check using the application's database pool. Never queues extra probes. */
@Service
public class DatabaseHealthService {
  public record DatabaseHealth(String status, String reason) {}

  private final DatabaseProbeRepository probeRepository;
  private final Duration timeout;
  private Future<DatabaseHealth> inFlight;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "database-readiness");
    thread.setDaemon(true);
    return thread;
  });

  public DatabaseHealthService(javax.sql.DataSource dataSource,
      @Value("${financial.health.database-timeout:2s}") Duration timeout) {
    this(new DatabaseProbeRepository(dataSource), timeout);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DatabaseHealthService(DatabaseProbeRepository probeRepository,
      @Value("${financial.health.database-timeout:2s}") Duration timeout) {
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0)
      throw new IllegalArgumentException("Database health timeout must be positive and at most 10 seconds");
    this.probeRepository = probeRepository;
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
    try {
      return probeRepository.probe(timeout)
          ? new DatabaseHealth("UP", null) : down("PROBE_FAILED");
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
