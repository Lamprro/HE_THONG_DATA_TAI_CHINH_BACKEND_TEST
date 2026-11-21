package com.hethongdata.taichinh.repository.jdbc.localai;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LocalAiTrainingQueryRepository {
  private final JdbcTemplate db;

  public LocalAiTrainingQueryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Integer countActiveSecurities() {
    return db.queryForObject("SELECT count(*) FROM securities WHERE is_active = true", Integer.class);
  }

  public Integer countStocksWithCanonicalPrices() {
    return db.queryForObject("SELECT count(DISTINCT security_id) FROM market_prices WHERE is_canonical = true", Integer.class);
  }

  public Integer countEligibleStocks() {
    return db.queryForObject(
        "SELECT count(DISTINCT p.security_id) FROM market_prices p " +
        "WHERE p.is_canonical = true AND p.interval_code = '1d' AND p.price_date >= '2021-01-01'",
        Integer.class);
  }

  public Integer countBadOhlc() {
    return db.queryForObject(
        "SELECT count(*) FROM market_prices " +
        "WHERE is_canonical = true AND interval_code = '1d' AND " +
        "(low_price > 0 AND low_price <= high_price " +
        " AND open_price BETWEEN low_price AND high_price " +
        " AND close_price BETWEEN low_price AND high_price) IS NOT TRUE",
        Integer.class);
  }

  public String findMaxPriceDate() {
    return db.queryForObject("SELECT max(price_date)::text FROM market_prices WHERE is_canonical = true", String.class);
  }

  public String findMaxStatementDate() {
    return db.queryForObject(
        "SELECT max(p.end_date)::text FROM financial_statements s JOIN financial_periods p ON p.id = s.financial_period_id WHERE s.is_current = true",
        String.class);
  }

  public Integer countIndexDays(String indexCode) {
    return db.queryForObject(
        "SELECT count(*) FROM index_prices p JOIN market_indices i ON i.id = p.market_index_id " +
        "WHERE p.is_canonical = true AND p.interval_code = '1d' AND i.code = ?",
        Integer.class,
        indexCode);
  }

  public List<Map<String, Object>> findRunByIdempotencyKey(UUID idempotencyKey) {
    return db.queryForList(
        "SELECT id, status, trigger, reason, requested_by, idempotency_key, " +
        "started_at, finished_at, error, selected_model FROM local_ai_training_runs WHERE idempotency_key = ?",
        idempotencyKey);
  }

  public Integer countActiveTrainingRuns() {
    return db.queryForObject(
        "SELECT count(*) FROM local_ai_training_runs WHERE status IN ('QUEUED', 'RUNNING')",
        Integer.class);
  }

  public void insertBlockedRun(UUID runId, String trigger, String requestedBy, UUID idempotencyKey, String readinessSha, String protocolVersion) {
    db.update(
        "INSERT INTO local_ai_training_runs (id, trigger, reason, requested_by, idempotency_key, " +
        "status, readiness_sha256, protocol_version, error, started_at, finished_at) " +
        "VALUES (?, ?, ?, ?, ?, 'BLOCKED', ?, ?, 'BLOCKED_DATA_NOT_READY', now(), now())",
        runId, trigger, requestedBy, idempotencyKey, readinessSha, protocolVersion);
  }

  public void insertQueuedRun(UUID runId, String trigger, String reason, String requestedBy, UUID idempotencyKey, String readinessSha, String protocolVersion) {
    db.update(
        "INSERT INTO local_ai_training_runs (id, trigger, reason, requested_by, idempotency_key, " +
        "status, readiness_sha256, protocol_version, started_at) " +
        "VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?, now())",
        runId, trigger, reason, requestedBy, idempotencyKey, readinessSha, protocolVersion);
  }

  public void updateRunStatus(UUID runId, String status) {
    db.update("UPDATE local_ai_training_runs SET status = ? WHERE id = ?", status, runId);
  }

  public void updateRunSucceeded(UUID runId, String outputPath, String summaryJson, String gateJson, String selectedModel) {
    db.update(
        "UPDATE local_ai_training_runs SET status = 'SUCCEEDED', output_path = ?, " +
        "summary = ?::jsonb, gate = ?::jsonb, selected_model = ?, finished_at = now() WHERE id = ?",
        outputPath, summaryJson, gateJson, selectedModel, runId);
  }

  public void updateRunFailed(UUID runId, String error) {
    db.update("UPDATE local_ai_training_runs SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ?", error, runId);
  }

  public List<Map<String, Object>> listTrainingRuns(int limit) {
    return db.queryForList(
        "SELECT id, trigger, reason, requested_by, idempotency_key, status, " +
        "readiness_sha256, snapshot_sha256, protocol_version, output_path, " +
        "selected_model, summary::text, gate::text, started_at, finished_at, error " +
        "FROM local_ai_training_runs ORDER BY started_at DESC LIMIT ?",
        limit);
  }

  public List<Map<String, Object>> findTrainingRun(UUID id) {
    return db.queryForList(
        "SELECT id, trigger, reason, requested_by, idempotency_key, status, " +
        "readiness_sha256, snapshot_sha256, protocol_version, output_path, " +
        "selected_model, summary::text, gate::text, started_at, finished_at, error " +
        "FROM local_ai_training_runs WHERE id = ?",
        id);
  }

  public void publishPackage(UUID packageId, String manifestSha, String manifestContent, String reason) {
    db.update("UPDATE local_ai_packages SET status = 'SUPERSEDED' WHERE status = 'ACTIVE'");
    db.update("INSERT INTO local_ai_packages (id, manifest_sha256, manifest, status, registered_at) VALUES (?, ?, ?::jsonb, 'ACTIVE', now())", packageId, manifestSha, manifestContent);
    db.update("INSERT INTO admin_operation_events (id, event_type, target_id, reason) VALUES (?, 'LOCAL_AI_PACKAGE_PUBLISHED', ?, ?)", UUID.randomUUID(), packageId, reason);
  }

  public List<Map<String, Object>> findActivePackageIds() {
    return db.queryForList("SELECT id FROM local_ai_packages WHERE status = 'ACTIVE'");
  }

  public List<Map<String, Object>> findLatestSupersededPackageIds() {
    return db.queryForList("SELECT id FROM local_ai_packages WHERE status = 'SUPERSEDED' ORDER BY registered_at DESC LIMIT 1");
  }

  public void rollbackPackage(UUID currentActive, UUID prevPackage, String reason) {
    db.update("UPDATE local_ai_packages SET status = 'ROLLED_BACK' WHERE id = ?", currentActive);
    db.update("UPDATE local_ai_packages SET status = 'ACTIVE' WHERE id = ?", prevPackage);
    db.update("INSERT INTO admin_operation_events (id, event_type, target_id, reason) VALUES (?, 'LOCAL_AI_PACKAGE_ROLLED_BACK', ?, ?)", UUID.randomUUID(), currentActive, reason);
  }
}
