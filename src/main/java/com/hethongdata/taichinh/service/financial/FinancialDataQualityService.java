package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FinancialDataQualityService {
 private final IngestionService ingest;private final RawPayloadJpaRepository raws;
 private final DataSourceJpaRepository sources;private final FinancialDisclosureDocumentStore documents;
 private final JdbcTemplate jdbc;
 public FinancialDataQualityService(IngestionService ingest,RawPayloadJpaRepository raws,DataSourceJpaRepository sources,FinancialDisclosureDocumentStore documents,JdbcTemplate jdbc){this.ingest=ingest;this.raws=raws;this.sources=sources;this.documents=documents;this.jdbc=jdbc;}
 public Map<String,Object> refresh(String symbol,Integer year) {
  if(!"FPT".equals(symbol)||(year!=null&&(year<2000||year>2100)))throw new IllegalArgumentException("Supported issuer FPT; year 2000..2100");
  var source=sources.findByCodeIgnoreCase("FPT_IR").orElseThrow(()->new IllegalStateException("Apply financial metadata migration first"));
  if(!"fpt-ir".equals(source.getProvider())||!"https://fpt.com".equals(source.getBaseUrl())||!source.isActive())throw new IllegalStateException("Official source identity mismatch");
  var req=new ManualIngestionRequest();req.setOperation(ExternalOperation.FINANCIAL_DISCLOSURES);req.setProvider("fpt-ir");req.setSymbol(symbol);req.setDataSourceCode("FPT_IR");req.setParameters(year==null?Map.of():Map.of("year",year.toString()));
  var response=ingest.ingest(req);var raw=raws.findById(response.getRawPayloadId()).orElseThrow();
  var saved=new LinkedHashMap<>(documents.save(raw.getId(),raw.getPayload()));saved.put("ingestionRunId",response.getRunId());saved.put("upstreamStatus",response.getUpstreamStatus());return saved;
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
  return Map.of("symbol",symbol,"canonical",counts,"officialDocuments",jdbc.queryForObject("SELECT count(*) FROM financial_disclosure_documents WHERE symbol=?",Long.class,symbol),"trainingReadiness","REQUIRES_VALUE_VINTAGE_REVIEW","supportedOfficialIssuers",List.of("FPT"),"serverTime",java.time.Instant.now().toString());
 }
}
