import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.hethongdata.taichinh.service.financial.FinancialReconciliationEvidenceService;

/** Imports reviewed real-source values only. Isolated pass is required before writing review evidence. */
public class FinancialEvidenceReview {
 public static void main(String[] args)throws Exception {
  var config=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]))){config.load(r);}
  var json=new ObjectMapper();Path input=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
  boolean apply=args[3].equals("apply");String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
  if(apply){var receipt=json.readTree(Files.readString(out.resolve("isolate.json")));if(!receipt.path("status").asText().equals("PASS")||!hash.equals(receipt.path("inputSha256").asText()))throw new IllegalStateException("Isolated review required");}
  try(var c=DriverManager.getConnection(FinancialMetadataMigration.resolve(config.getProperty("spring.datasource.url")),FinancialMetadataMigration.resolve(config.getProperty("spring.datasource.username")),FinancialMetadataMigration.resolve(config.getProperty("spring.datasource.password")))){
   c.setAutoCommit(false);var jdbc=new JdbcTemplate(new SingleConnectionDataSource(c,true));
   try(var sql=c.createStatement()) {
    sql.execute("SET LOCAL statement_timeout='60s'");sql.execute("SET LOCAL lock_timeout='5s'");
    if(!apply){
     String schema="financial_review_test_"+UUID.randomUUID().toString().replace("-","");
     sql.execute("CREATE SCHEMA "+schema);sql.execute("SET LOCAL search_path TO "+schema);
     // Only the review table is written; all source queries are explicitly available read-only in public.
     sql.execute("CREATE TABLE financial_reconciliation_evidence (LIKE public.financial_reconciliation_evidence INCLUDING ALL)");
     sql.execute("ALTER TABLE financial_reconciliation_evidence ALTER COLUMN id DROP DEFAULT");
     sql.execute("CREATE SEQUENCE review_id");sql.execute("ALTER TABLE financial_reconciliation_evidence ALTER COLUMN id SET DEFAULT nextval('review_id')");
     sql.execute("SET LOCAL search_path TO "+schema+",public");
    }
    var service=new FinancialReconciliationEvidenceService(jdbc,json);var results=new ArrayList<Object>();int negative=0;
    for(var value:json.readTree(Files.readString(input))) {
     var review=(ObjectNode)value.deepCopy();
     UUID item=jdbc.queryForObject("""
      SELECT i.id FROM financial_statement_items i JOIN financial_statements s ON s.id=i.financial_statement_id
      JOIN securities se ON se.id=s.security_id JOIN financial_periods p ON p.id=s.financial_period_id
      WHERE se.symbol=? AND p.end_date=?::date AND p.period_type IN ('Q1','Q2','Q3','Q4')
      AND s.is_current AND s.is_canonical AND s.statement_type='INCOME_STATEMENT' AND i.item_code=?
      """,UUID.class,review.path("symbol").asText(),review.path("periodEnd").asText(),review.path("target").asText());
     review.put("itemId",item.toString());
     for(var op:review.path("operands")) {
      UUID raw=UUID.fromString(op.path("rawId").asText());
      String uri=jdbc.queryForObject("SELECT run.request_url FROM raw_payloads r JOIN ingestion_runs run ON run.id=r.ingestion_run_id WHERE r.id=?",String.class,raw);
      String origin=uri.substring(uri.indexOf("/api/v1/proxy/fpt-ir")+"/api/v1/proxy/fpt-ir".length());
      Long doc=jdbc.queryForObject("SELECT id FROM financial_disclosure_documents WHERE symbol=? AND metadata->>'document_url'=? ORDER BY observed_at DESC,parser_version DESC,id DESC LIMIT 1",Long.class,review.path("symbol").asText(),"https://fpt.com"+origin);
      ((ObjectNode)op).put("documentId",doc);
     }
     if(!apply){
      var bad=review.deepCopy();((ObjectNode)bad.path("operands").get(0)).put("sha256","a".repeat(64));
      try{service.save(bad);throw new IllegalStateException("Corrupt source accepted");}catch(IllegalArgumentException e){negative++;}
      var badMath=review.deepCopy();((ObjectNode)badMath.path("operands").get(0)).put("parentValue",1);
      try{service.save(badMath);throw new IllegalStateException("Invalid arithmetic accepted");}catch(IllegalArgumentException e){negative++;}
     }
     var receipt=new LinkedHashMap<String,Object>();receipt.put("periodEnd",review.path("periodEnd").asText());receipt.put("first",service.save(review));receipt.put("replay",service.save(review));results.add(receipt);
    }
    if(apply)c.commit();else c.rollback();
    var receipt=Map.of("status","PASS","inputSha256",hash,"results",results,"negativeRejected",negative,"completedAt",java.time.Instant.now().toString());
    json.writerWithDefaultPrettyPrinter().writeValue(out.resolve(apply?"apply.json":"isolate.json").toFile(),receipt);System.out.println(json.writeValueAsString(receipt));
   }
  }
 }
}
