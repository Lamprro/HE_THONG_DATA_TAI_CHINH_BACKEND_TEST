package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable official document metadata. Does not certify or overwrite vendor statements. */
@Service
public class FinancialDisclosureDocumentStore {
 private final JdbcTemplate jdbc;private final ObjectMapper json;
 public FinancialDisclosureDocumentStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
 public static void validate(JsonNode root) {
  require("financial.disclosures.v1".equals(root.path("schema_version").asText()),"DISCLOSURE_CONTRACT");
  require("fpt-ir".equals(root.path("provider").asText())&&"FPT".equals(root.path("symbol").asText()),"DISCLOSURE_IDENTITY");
  require("https://fpt.com/vi/nha-dau-tu/thong-tin-cong-bo".equals(root.path("source_url").asText()),"DISCLOSURE_SOURCE");
  require(root.path("source_sha256").asText().matches("[a-f0-9]{64}"),"DISCLOSURE_HASH");
  require(root.path("data").isArray()&&root.path("count").asInt(-1)==root.path("data").size()&&root.path("data").size()<=1000,"DISCLOSURE_COUNT");
  var keys=new HashSet<String>();
  for(var d:root.path("data")) {
   String id=d.path("document_id").asText(),hash=d.path("source_record_sha256").asText();
   require(!id.isBlank()&&id.length()<=160&&hash.matches("[a-f0-9]{64}")&&keys.add(id+":"+hash),"DOCUMENT_IDENTITY");
   require(id.equals(d.path("source_record").path("id").asText()),"DOCUMENT_ID_EVIDENCE");
   require("FPT".equals(d.path("symbol").asText())&&("FPT_IR:"+id).equals(d.path("report_version_id").asText()),"DOCUMENT_SYMBOL_VERSION");
   require(d.path("document_url").asText().matches("https://fpt\\.com/api/media/[A-Za-z0-9_.-]+\\.pdf"),"DOCUMENT_URL");
   LocalDate period=LocalDate.parse(d.path("period_end").asText()),published=LocalDate.parse(d.path("published_on").asText());
   require(period.getMonthValue()%3==0&&period.getDayOfMonth()==period.lengthOfMonth(),"QUARTER_END");
   require(!published.isBefore(period)&&!published.isAfter(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))),"PUBLICATION_DATE");
   require("actualPublishedAt".equals(d.path("publication_evidence").path("field").asText())&&published.toString().equals(d.path("source_record").path("actualPublishedAt").asText())&&published.toString().equals(d.path("publication_evidence").path("value").asText()),"PUBLICATION_EVIDENCE");
   var available=OffsetDateTime.parse(d.path("available_at").asText());
   require("DAY".equals(d.path("publication_precision").asText())&&available.toInstant().equals(published.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().minusNanos(1000)),"DAY_PRECISION_AVAILABILITY");
   require(Set.of("UNKNOWN","CONSOLIDATED","SEPARATE").contains(d.path("report_scope").asText()),"REPORT_SCOPE");
   String title=d.path("source_record").path("name").asText();
   require(!title.isBlank()&&title.equals(d.path("title").asText())&&title.equals(d.path("scope_evidence").path("value").asText()),"SCOPE_EVIDENCE");
   String plain=java.text.Normalizer.normalize(title.replace('đ','d').replace('Đ','D'),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
   require(!"CONSOLIDATED".equals(d.path("report_scope").asText())||plain.contains("hop nhat"),"CONSOLIDATED_EVIDENCE");
   require(!"SEPARATE".equals(d.path("report_scope").asText())||plain.contains("rieng le"),"SEPARATE_EVIDENCE");
   require(("https://fpt.com"+d.path("source_record").path("pdfUrl").asText()).equals(d.path("document_url").asText()),"DOCUMENT_URL_EVIDENCE");
   require(Set.of("UNKNOWN","RESTATED").contains(d.path("revision_status").asText()),"REVISION_STATUS");
   require(!"RESTATED".equals(d.path("revision_status").asText())||List.of("dieu chinh","dinh chinh","thay the","restated").stream().anyMatch(plain::contains),"REVISION_EVIDENCE");
   require(d.path("training_eligible").isBoolean()&&!d.path("training_eligible").asBoolean()&&"UNVERIFIED".equals(d.path("vendor_value_binding").asText())&&"ARCHIVED_DISCLOSURE_RECONSTRUCTION".equals(d.path("vintage_status").asText()),"VINTAGE_NOT_CERTIFIED");
  }
 }
 @Transactional
 public Map<String,Object> save(UUID rawId,JsonNode root) {
  validate(root);int inserted=0;
  for(var d:root.path("data"))inserted+=jdbc.update("""
   INSERT INTO financial_disclosure_documents(source_code,symbol,document_id,record_sha256,listing_sha256,source_raw_payload_id,period_end,published_on,available_at,report_scope,revision_status,report_version_id,metadata)
   VALUES ('FPT_IR','FPT',?,?,?,?,?::date,?::date,?::timestamptz,?,?,?,?::jsonb)
   ON CONFLICT(source_code,document_id,record_sha256) DO NOTHING
   """,d.path("document_id").asText(),d.path("source_record_sha256").asText(),root.path("source_sha256").asText(),rawId,d.path("period_end").asText(),d.path("published_on").asText(),d.path("available_at").asText(),d.path("report_scope").asText(),d.path("revision_status").asText(),d.path("report_version_id").asText(),d.toString());
  return Map.of("rawPayloadId",rawId,"validatedDocuments",root.path("data").size(),"insertedDocuments",inserted,"trainingEligible",false,"vendorStatementsModified",0);
 }
 public List<Map<String,Object>> list(String symbol,int limit) {
  require(symbol.matches("[A-Z0-9._-]{1,20}")&&limit>=1&&limit<=200,"INVALID_QUERY");
  return jdbc.query("SELECT id,source_raw_payload_id,observed_at,metadata::text FROM financial_disclosure_documents WHERE symbol=? ORDER BY period_end DESC,published_on DESC,id DESC LIMIT ?",(rs,n)->{
   try {return Map.of("id",rs.getLong(1),"rawPayloadId",rs.getObject(2).toString(),"observedAt",rs.getTimestamp(3).toInstant().toString(),"document",json.readTree(rs.getString(4)));}
   catch(Exception e){throw new IllegalStateException("Stored document cannot be read",e);}
  },symbol,limit);
 }
}
