package com.hethongdata.taichinh.repository.jdbc.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialDisclosureJdbcRepository {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  public FinancialDisclosureJdbcRepository(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public int countMatchingRawPayload(UUID rawId, String payloadJson, String sourceSymbol, String sourceCode) {
    Integer matched = db.queryForObject(
        """
        SELECT count(*) FROM raw_payloads r JOIN data_sources ds ON ds.id=r.data_source_id
        JOIN ingestion_runs run ON run.id=r.ingestion_run_id
        WHERE r.id=? AND r.payload=?::jsonb AND r.source_symbol=? AND ds.code=? AND run.status='SUCCESS'
        """,
        Integer.class, rawId, payloadJson, sourceSymbol, sourceCode);
    return matched == null ? 0 : matched;
  }

  public int insertDisclosureDocument(
      String sourceCode,
      String symbol,
      String documentId,
      String recordSha256,
      String listingSha256,
      UUID rawId,
      String periodEnd,
      String publishedOn,
      String availableAt,
      String reportScope,
      String revisionStatus,
      String reportVersionId,
      String metadataJson) {
    return db.update(
        """
        INSERT INTO financial_disclosure_documents(source_code,symbol,document_id,record_sha256,listing_sha256,source_raw_payload_id,period_end,published_on,available_at,report_scope,revision_status,report_version_id,metadata,parser_version)
        VALUES (?,?,?,?,?,?,?::date,?::date,?::timestamptz,?,?,?,?::jsonb,2)
        ON CONFLICT(source_code,document_id,record_sha256,parser_version) DO NOTHING
        """,
        sourceCode, symbol, documentId, recordSha256, listingSha256, rawId,
        periodEnd, publishedOn, availableAt, reportScope, revisionStatus, reportVersionId, metadataJson);
  }

  public List<Map<String, Object>> listDocuments(String symbol, int limit) {
    return db.query(
        """
        SELECT id,source_raw_payload_id,observed_at,metadata::text
        FROM (
            SELECT DISTINCT ON(source_code,document_id) *
            FROM financial_disclosure_documents WHERE symbol=?
            ORDER BY source_code,document_id,observed_at DESC,parser_version DESC,id DESC
        ) latest
        ORDER BY period_end DESC,published_on DESC,id DESC LIMIT ?
        """,
        (rs, n) -> {
          try {
            return Map.of(
                "id", rs.getLong(1),
                "rawPayloadId", rs.getObject(2).toString(),
                "observedAt", rs.getTimestamp(3).toInstant().toString(),
                "document", json.readTree(rs.getString(4)));
          } catch (Exception e) {
            throw new IllegalStateException("Stored document cannot be read", e);
          }
        },
        symbol, limit);
  }
}
