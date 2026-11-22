package com.hethongdata.taichinh.service.financial;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reports coverage for canonical financial statements. */
@Service
public class FinancialDataQualityService {
 private final JdbcTemplate jdbc;

 public FinancialDataQualityService(JdbcTemplate jdbc) {
  this.jdbc = jdbc;
 }

 public Map<String,Object> status(String symbol) {
  if(symbol==null||!symbol.matches("[A-Z0-9._-]{1,20}"))throw new IllegalArgumentException("Invalid symbol");
  var counts=jdbc.queryForMap("""
   SELECT count(*) AS canonical_statements,
    count(*) FILTER(WHERE s.published_at IS NULL) AS missing_publication,
    count(*) FILTER(WHERE s.report_scope IS NULL OR s.report_scope='UNKNOWN') AS missing_scope,
    count(*) FILTER(WHERE COALESCE(s.metadata_provenance#>>'{source,revision_status}','UNKNOWN')='UNKNOWN') AS missing_revision,
    count(*) FILTER(WHERE s.unit_scale IS NULL OR s.currency IS NULL) AS missing_unit
   FROM financial_statements s JOIN securities se ON se.id=s.security_id
   WHERE se.symbol=? AND s.is_current AND s.is_canonical
   """,symbol);
  return Map.of("symbol",symbol,"canonical",counts,"trainingReadiness","REQUIRES_VALUE_VINTAGE_REVIEW","serverTime",java.time.Instant.now().toString());
 }
 public List<Map<String,Object>> coverage() {
  var result=jdbc.queryForList("""
   SELECT se.symbol,count(s.id) AS canonical_statements,
    count(s.id) FILTER(WHERE s.published_at IS NULL) AS missing_publication,
    count(s.id) FILTER(WHERE s.report_scope IS NULL OR s.report_scope='UNKNOWN') AS missing_scope
   FROM securities se JOIN financial_statements s ON s.security_id=se.id AND s.is_current AND s.is_canonical
   GROUP BY se.id,se.symbol ORDER BY se.symbol
   """);
  result.forEach(row -> row.put("trainingStatus", "REQUIRES_VALUE_VINTAGE_REVIEW"));
  return result;
 }
}
