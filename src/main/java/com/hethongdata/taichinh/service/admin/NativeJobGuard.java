package com.hethongdata.taichinh.service.admin;

import com.hethongdata.taichinh.repository.support.AdvisoryLockRepository;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Session lock covers native manual, queue and scheduler entry points across instances. */
@Component
public class NativeJobGuard {
  private final AdvisoryLockRepository lockRepository;

  public NativeJobGuard(javax.sql.DataSource source) {
    this(new AdvisoryLockRepository(source));
  }

  public NativeJobGuard(AdvisoryLockRepository lockRepository) {
    this.lockRepository = lockRepository;
  }

  public <T> T run(UUID id, Supplier<T> work) {
    try (var connection = lockRepository.openConnection()) {
      String key = "financial-native-job:" + id;
      if (!lockRepository.tryLock(connection, key)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "This native job is already running on another worker");
      }
      try {
        return work.get();
      } finally {
        try {
          lockRepository.unlock(connection, key);
        } catch (Exception unlockFailure) {
          connection.abort(Runnable::run);
        }
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Native job lock unavailable", e);
    }
  }
}
