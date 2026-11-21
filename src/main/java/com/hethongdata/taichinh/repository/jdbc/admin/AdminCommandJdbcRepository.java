package com.hethongdata.taichinh.repository.jdbc.admin;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminCommandJdbcRepository {
  private final JdbcTemplate db;

  public AdminCommandJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> findPrevious(UUID idempotencyKey) {
    return db.queryForList("select id,request_hash,parent_command_id from admin_commands where idempotency_key=?", idempotencyKey);
  }

  public void insertQueued(UUID id, UUID idempotencyKey, String hash, String action, String resource,
      String reason, String inputJson, UUID parent, String principal) {
    db.update("insert into admin_commands(id,idempotency_key,request_hash,action,resource_key,reason,status,request_json,parent_command_id,principal) "
        + "values (?,?,?,?,?,?,'QUEUED',cast(? as jsonb),?,?)",
        id, idempotencyKey, hash, action, resource, reason, inputJson, parent, principal);
  }

  public List<Map<String, Object>> getReplayParams(UUID runId) {
    return db.queryForList("select r.request_query_params::text,s.code from ingestion_runs r "
        + "join data_sources s on s.id=r.data_source_id where r.id=?", runId);
  }

  public List<Map<String, Object>> findById(UUID id) {
    return db.queryForList("select id,idempotency_key,action,resource_key,reason,principal,status,request_json::text,"
        + "result_json::text,progress_json::text,parent_command_id,error_message,created_at,started_at,finished_at,acknowledgement_note from admin_commands where id=?", id);
  }

  public int acknowledge(UUID id, String note) {
    return db.update("update admin_commands set status='ACKNOWLEDGED', acknowledgement_note=? where id=? and status='UNKNOWN'", note, id);
  }

  public int cancelQueued(UUID id, String reason) {
    return db.update("update admin_commands set status='CANCELLED',finished_at=now(),acknowledgement_note=? where id=? and status='QUEUED'", reason, id);
  }

  public boolean validationResultExists(UUID validationResultId) {
    Integer count = db.queryForObject("select count(*) from validation_results where id=?", Integer.class, validationResultId);
    return count != null && count == 1;
  }

  public Map<String, Object> claimQueued(UUID ownerId, Set<String> actions) {
    String placeholders = String.join(",", Collections.nCopies(actions.size(), "?"));
    var rows = db.queryForList("select id,action,request_json::text from admin_commands where status='QUEUED' "
        + "and action in (" + placeholders + ") order by created_at,id for update skip locked limit 1", actions.toArray());
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    db.update("update admin_commands set status='RUNNING',owner_id=?,started_at=now(),lease_until=now()+interval '90 seconds' where id=?", ownerId, row.get("id"));
    return row;
  }

  public void recordSuccess(UUID id, UUID ownerId, String resultJson) {
    db.update("update admin_commands set status='SUCCEEDED',result_json=cast(? as jsonb),finished_at=now() "
        + "where id=? and status='RUNNING' and owner_id=?", resultJson, id, ownerId);
  }

  public void recordFailure(UUID id, UUID ownerId, String errorMessage, String resultJson) {
    db.update("update admin_commands set status='FAILED',error_message=?,result_json=cast(? as jsonb),finished_at=now() "
        + "where id=? and status='RUNNING' and owner_id=?", errorMessage, resultJson, id, ownerId);
  }

  public void updateProgress(UUID id, UUID ownerId, String progressJson) {
    db.update("update admin_commands set progress_json=cast(? as jsonb) where id=? and owner_id=? and status='RUNNING'", progressJson, id, ownerId);
  }

  public void extendLeases(Set<UUID> inFlight, UUID ownerId) {
    for (UUID id : inFlight) {
      db.update("update admin_commands set lease_until=now()+interval '90 seconds' where id=? and status='RUNNING' and owner_id=?", id, ownerId);
    }
    db.update("update admin_commands set status='UNKNOWN',finished_at=now(),error_message='Worker lease expired. Check original native runs before retrying.' "
        + "where status='RUNNING' and lease_until<now()");
  }

  public void recordEvent(UUID id, String type, UUID commandId, String reason, String principal) {
    db.update("insert into admin_operation_events(id,event_type,command_id,reason,principal) values (?,?,?,?,?)", id, type, commandId, reason, principal);
  }
}
