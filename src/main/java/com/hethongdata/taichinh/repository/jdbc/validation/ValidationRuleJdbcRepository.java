package com.hethongdata.taichinh.repository.jdbc.validation;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ValidationRuleJdbcRepository {
  private final JdbcTemplate db;

  public ValidationRuleJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<String> findLlmOutputRulesJson() {
    return db.queryForList("""
        SELECT jsonb_build_object('id',id,'code',code,'executor',executor_key,'severity',severity,
            'config',rule_config,'domain',data_domain)::text
        FROM validation_rules WHERE data_domain='LLM_OUTPUT' AND is_active ORDER BY code
        """, String.class);
  }

  public List<String> findLlmForecastRulesJson() {
    return db.queryForList("""
        SELECT jsonb_build_object('id',id,'code',code,'executor',executor_key,'severity',severity,'config',rule_config)::text
        FROM validation_rules WHERE data_domain='LLM_FORECAST' AND is_active ORDER BY code
        """, String.class);
  }

  public List<Map<String, Object>> findAuditsByRound(UUID runId, UUID roundId) {
    return db.queryForList("""
        SELECT rule_code,result_status,rule_snapshot::text snapshot FROM validation_results
        WHERE llm_run_id=? AND validation_round_id=? AND validation_target='LLM_OUTPUT'
        """, runId, roundId);
  }

  public void insertValidationResult(
      UUID id,
      long ruleId,
      String code,
      String severity,
      String resultStatus,
      String handlingStatus,
      UUID runId,
      UUID roundId,
      String ruleSnapshotJson,
      String observedValueJson,
      String expectedValue,
      String message) {
    db.update("""
        INSERT INTO validation_results(id,validation_rule_id,rule_code,severity,result_status,handling_status,
            checked_at,validation_target,llm_run_id,validation_round_id,rule_snapshot,observed_value,expected_value,message)
        VALUES (?,?,?,?,?,?,now(),'LLM_OUTPUT',?,?,?::jsonb,?,?,?)
        """,
        id, ruleId, code, severity, resultStatus, handlingStatus, runId, roundId,
        ruleSnapshotJson, observedValueJson, expectedValue, message);
  }
}
