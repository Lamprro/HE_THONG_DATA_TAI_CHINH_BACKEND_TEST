package com.hethongdata.taichinh.service.admin;

import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Session lock covers native manual, queue and scheduler entry points across instances. */
@Component
public class NativeJobGuard {
  private final DataSource source;
  public NativeJobGuard(DataSource source) {this.source=source;}
  public <T> T run(UUID id,Supplier<T> work) {
    try(var connection=source.getConnection()) {
      String key="financial-native-job:"+id;
      try(var query=connection.prepareStatement("select pg_try_advisory_lock(hashtext(?))")) {
        query.setString(1,key);try(var result=query.executeQuery()) {
          if(!result.next()||!result.getBoolean(1)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This native job is already running on another worker");
        }
      }
      try {return work.get();}
      finally {
        try(var query=connection.prepareStatement("select pg_advisory_unlock(hashtext(?))")) {query.setString(1,key);query.execute();}
        catch(Exception unlockFailure) {connection.abort(Runnable::run);}
      }
    } catch(java.sql.SQLException e) {throw new IllegalStateException("Native job lock unavailable",e);}
  }
}
