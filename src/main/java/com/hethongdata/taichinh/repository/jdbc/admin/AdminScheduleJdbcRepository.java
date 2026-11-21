package com.hethongdata.taichinh.repository.jdbc.admin;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminScheduleJdbcRepository {
  private final JdbcTemplate db;

  public AdminScheduleJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> getPolicy() {
    return db.queryForMap("select * from admin_schedule_policy where id='INGESTION'");
  }

  public int updatePolicy(boolean enabled, String jobCodesJson, String reason, long version) {
    return db.update(
        "update admin_schedule_policy set enabled=?,job_codes=cast(? as jsonb),version=version+1,reason=?,updated_at=now() where id='INGESTION' and version=?",
        enabled, jobCodesJson, reason, version);
  }

  public void updateHeartbeat() {
    db.update("update admin_schedule_policy set heartbeat_at=now() where id='INGESTION'");
  }

  public void recordSweepStarted() {
    db.update("update admin_schedule_policy set last_sweep_started_at=now(),last_error=null where id='INGESTION'");
  }

  public void recordSweepFinished() {
    db.update("update admin_schedule_policy set last_sweep_finished_at=now() where id='INGESTION'");
  }

  public void recordSweepError(String error) {
    db.update("update admin_schedule_policy set last_error=? where id='INGESTION'", error);
  }
}
