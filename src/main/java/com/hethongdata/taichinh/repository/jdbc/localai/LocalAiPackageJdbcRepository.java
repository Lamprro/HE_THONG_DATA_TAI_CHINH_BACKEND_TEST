package com.hethongdata.taichinh.repository.jdbc.localai;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LocalAiPackageJdbcRepository {
  private final JdbcTemplate db;

  public LocalAiPackageJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> findPackages() {
    return db.queryForList("SELECT id,manifest_sha256,status,registered_at,manifest->'models' AS models FROM local_ai_packages ORDER BY registered_at DESC");
  }

  public List<Map<String, Object>> findInputs(UUID packageId) {
    return db.queryForList("SELECT model_id,ticker,data_as_of,input_sha256 FROM local_ai_package_inputs WHERE package_id=? ORDER BY model_id,ticker", packageId);
  }

  public Map<String, Object> findRun(UUID runId) {
    return db.queryForMap("SELECT * FROM local_ai_package_runs WHERE id=?", runId);
  }

  public List<Map<String, Object>> findRuns(UUID packageId) {
    return db.queryForList("SELECT id,request_id,model_id,ticker,role,status,error_code,http_status,latency_ms,started_at,completed_at FROM local_ai_package_runs WHERE package_id=? ORDER BY started_at DESC LIMIT 500", packageId);
  }

  public List<Map<String, Object>> findResearchPackage(UUID packageId) {
    return db.queryForList("SELECT manifest,manifest_sha256 FROM local_ai_packages WHERE id=? AND status='RESEARCH_ONLY'", packageId);
  }

  public int claimRun(UUID id, UUID requestId, UUID packageId, String modelId, String ticker, String role, String digest) {
    return db.update("INSERT INTO local_ai_package_runs(id,request_id,package_id,model_id,ticker,role,request_hash,status) VALUES(?,?,?,?,?,?,?,'RUNNING') ON CONFLICT DO NOTHING", id, requestId, packageId, modelId, ticker, role, digest);
  }

  public Map<String, Object> findPriorRun(UUID packageId, UUID requestId) {
    return db.queryForMap("SELECT id,request_hash,status,response_json FROM local_ai_package_runs WHERE package_id=? AND request_id=? AND cached_from IS NULL", packageId, requestId);
  }

  public void recordCachedRun(UUID id, UUID requestId, UUID packageId, String modelId, String ticker, String role, String digest, String status, Object cachedFrom, String responseJson, String error) {
    db.update("INSERT INTO local_ai_package_runs(id,request_id,package_id,model_id,ticker,role,request_hash,status,cached_from,response_json,error_code,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,now())", id, requestId, packageId, modelId, ticker, role, digest, status, cachedFrom, responseJson, error);
  }

  public List<Map<String, Object>> findInputSnapshot(UUID packageId, String modelId, String ticker) {
    return db.queryForList("SELECT input_payload,input_sha256,data_as_of FROM local_ai_package_inputs WHERE package_id=? AND model_id=? AND ticker=?", packageId, modelId, ticker);
  }

  public void updateRunRequest(UUID id, String requestJson) {
    db.update("UPDATE local_ai_package_runs SET request_json=?::jsonb WHERE id=?", requestJson, id);
  }

  public void updateRunChecks(UUID id, String checkedJson) {
    db.update("UPDATE local_ai_package_runs SET checks=?::jsonb WHERE id=?", checkedJson, id);
  }

  public void recordRunSuccess(UUID id, UUID packageId, String modelId, String ticker, String role, long latency, String responseJson, String checksJson, String targetPeriod, OffsetDateTime issuedAt, String resultJson) {
    db.update("UPDATE local_ai_package_runs SET status='SUCCEEDED',http_status=200,latency_ms=?,response_json=?::jsonb,checks=?::jsonb,completed_at=now() WHERE id=?", latency, responseJson, checksJson, id);
    db.update("INSERT INTO local_ai_package_predictions(id,run_id,package_id,model_id,ticker,role,target_period,issued_at,prediction,readiness) VALUES(?,?,?,?,?,?,?,?,?::jsonb,'RESEARCH_ONLY')", UUID.randomUUID(), id, packageId, modelId, ticker, role, targetPeriod, issuedAt, resultJson);
  }

  public void recordRunFailure(UUID id, String status, Integer httpStatus, long latency, String diagnosticJson, String error) {
    db.update("UPDATE local_ai_package_runs SET status=?,http_status=?,latency_ms=?,response_json=?::jsonb,error_code=?,completed_at=now() WHERE id=?", status, httpStatus, latency, diagnosticJson, error, id);
  }

  public List<Map<String, Object>> recoverStaleRuns(UUID packageId) {
    return db.queryForList("UPDATE local_ai_package_runs SET status='PROVIDER_FAILED',error_code='STALE_RUN_AUDIT_INCOMPLETE',completed_at=now() WHERE package_id=? AND status='RUNNING' AND started_at<now()-interval '5 minutes' RETURNING id,status,error_code", packageId);
  }
}
