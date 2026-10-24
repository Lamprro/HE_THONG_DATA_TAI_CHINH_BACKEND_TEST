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
  return refresh(symbol,year,1);
 }
 public Map<String,Object> refresh(String symbol,Integer year,int page) {
  var official=OfficialFinancialSources.forSymbol(symbol);
  if((year!=null&&(year<2000||year>2099))||page<1||page>100||(!Set.of("HPG","HSG").contains(symbol)&&page!=1))throw new IllegalArgumentException("Invalid year or page");
  var source=sources.findByCodeIgnoreCase(official.code()).orElseThrow(()->new IllegalStateException("Apply financial metadata migration first"));
  if(!official.provider().equals(source.getProvider())||!official.documentOrigin().equals(source.getBaseUrl())||!source.isActive())throw new IllegalStateException("Official source identity mismatch");
  var parameters=new HashMap<String,String>();parameters.put("page",String.valueOf(page));
  if(year!=null)parameters.put("year",year.toString());
  var req=new ManualIngestionRequest();req.setOperation(ExternalOperation.FINANCIAL_DISCLOSURES);req.setProvider(official.provider());req.setSymbol(symbol);req.setDataSourceCode(official.code());req.setParameters(parameters);
  var response=ingest.ingest(req);var raw=raws.findById(response.getRawPayloadId()).orElseThrow();
  var saved=new LinkedHashMap<>(documents.save(raw.getId(),raw.getPayload()));saved.put("ingestionRunId",response.getRunId());saved.put("upstreamStatus",response.getUpstreamStatus());saved.put("nextPage",raw.getPayload().path("next_page"));return saved;
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
  return Map.of("symbol",symbol,"canonical",counts,"officialDocuments",jdbc.queryForObject("SELECT count(DISTINCT (source_code,document_id)) FROM financial_disclosure_documents WHERE symbol=?",Long.class,symbol),"trainingReadiness","REQUIRES_VALUE_VINTAGE_REVIEW","supportedOfficialIssuers",OfficialFinancialSources.symbols(),"serverTime",java.time.Instant.now().toString());
 }
 public List<Map<String,Object>> coverage() {
  var result=jdbc.queryForList("""
   SELECT se.symbol,count(s.id) AS canonical_statements,
    count(s.id) FILTER(WHERE s.published_at IS NULL) AS missing_publication,
    count(s.id) FILTER(WHERE s.report_scope IS NULL OR s.report_scope='UNKNOWN') AS missing_scope,
    (SELECT count(DISTINCT (d.source_code,d.document_id)) FROM financial_disclosure_documents d WHERE d.symbol=se.symbol) AS official_documents,
    (SELECT count(*) FROM financial_disclosure_facts f WHERE f.security_id=se.id) AS reviewed_facts
   FROM securities se JOIN financial_statements s ON s.security_id=se.id AND s.is_current AND s.is_canonical
   GROUP BY se.id,se.symbol ORDER BY se.symbol
   """);
  result.forEach(row->{row.put("officialAdapterSupported",OfficialFinancialSources.symbols().contains(row.get("symbol")));row.put("trainingStatus","REQUIRES_VALUE_VINTAGE_REVIEW");});
  return result;
 }
}
