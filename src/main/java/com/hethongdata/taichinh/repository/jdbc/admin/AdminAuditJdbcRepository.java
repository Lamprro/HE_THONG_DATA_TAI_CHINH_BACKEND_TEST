package com.hethongdata.taichinh.repository.jdbc.admin;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAuditJdbcRepository {
  private final JdbcTemplate db;

  public AdminAuditJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void recordHttpMutation(UUID id, String method, String requestUri, int status) {
    db.update(
        "insert into admin_operation_events(id,event_type,http_method,request_path,http_status) values (?,'HTTP_MUTATION',?,?,?)",
        id, method, requestUri, status);
  }

  public void recordEvent(UUID id, String eventType, UUID targetId, String reason) {
    db.update(
        "insert into admin_operation_events(id,event_type,target_id,reason) values (?,?,?,?)",
        id, eventType, targetId, reason);
  }

  public void recordCommand(UUID id, String eventType, String commandType, UUID targetId, String reason) {
    db.update(
        "insert into admin_operation_events(id,event_type,command_type,target_id,reason) values (?,?,?,?,?)",
        id, eventType, commandType, targetId, reason);
  }
}
