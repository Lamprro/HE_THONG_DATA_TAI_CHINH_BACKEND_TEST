import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.hethongdata.taichinh.service.financial.FinancialValueBindingService;

/** Real PostgreSQL checks in a rollback-only schema, then apply the same DDL by checksum. */
public class FinancialVersionMigration {
 public static void main(String[] args)throws Exception {
  var config=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]))){config.load(r);}
  Path migration=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
  boolean apply=args[3].equals("apply");var json=new ObjectMapper();
  String ddl=Files.readString(migration),sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(migration)));
  if(apply){var receipt=json.readTree(Files.readString(out.resolve("isolate.json")));if(!receipt.path("status").asText().equals("PASS")||!sha.equals(receipt.path("sha256").asText()))throw new IllegalStateException("Isolation receipt required");}
  try(var connection=DriverManager.getConnection(FinancialMetadataMigration.resolve(config.getProperty("spring.datasource.url")),FinancialMetadataMigration.resolve(config.getProperty("spring.datasource.username")),FinancialMetadataMigration.resolve(config.getProperty("spring.datasource.password")))){
   connection.setAutoCommit(false);var result=new LinkedHashMap<String,Object>();
   try(var sql=connection.createStatement()){
    sql.execute("SET LOCAL lock_timeout='5s'");sql.execute("SET LOCAL statement_timeout='60s'");
    if(!apply){
     String schema="financial_versions_test_"+UUID.randomUUID().toString().replace("-","");
     sql.execute("CREATE SCHEMA "+schema);sql.execute("SET LOCAL search_path TO "+schema);
     for(String table:List.of("securities","financial_periods","financial_statements","financial_statement_items","financial_disclosure_facts","financial_disclosure_documents","data_sources","raw_payloads")){
      sql.execute("CREATE TABLE "+table+" (LIKE public."+table+" INCLUDING ALL)");
      if(!table.equals("raw_payloads"))sql.execute("INSERT INTO "+table+" SELECT * FROM public."+table);
     }
     sql.execute("INSERT INTO raw_payloads SELECT r.* FROM public.raw_payloads r WHERE r.id IN (SELECT source_document_raw_id FROM financial_disclosure_facts)");
     sql.execute(ddl);sql.execute(ddl);
     var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
     var service=new FinancialValueBindingService(jdbc);
     var first=service.reconcile("FPT");var second=service.reconcile("FPT");
     if(((Number)first.get("insertedBindings")).intValue()==0 || ((Number)second.get("insertedBindings")).intValue()!=0)throw new IllegalStateException("Reconciliation idempotency failed");
     var historical=service.list("FPT",java.time.Instant.parse("2025-01-01T00:00:00Z"),500);
     if(historical.stream().anyMatch(r->Boolean.TRUE.equals(r.get("observed_value_available"))))throw new IllegalStateException("Archived fact leaked into historical dataset");
     int negative=0;
     for(String change:List.of("comparison='EXACT_VALUE_MATCH',canonical_value=document_value+1","usable_from=source_available_at-interval '1 day'","document_sha256='bad'")){
      var savepoint=connection.setSavepoint();
      try{sql.executeUpdate("UPDATE financial_value_bindings SET "+change);throw new IllegalStateException("Invalid binding accepted");}
      catch(SQLException e){if(!"23514".equals(e.getSQLState()))throw e;negative++;}finally{connection.rollback(savepoint);}
     }
     sql.executeUpdate("UPDATE financial_statement_items SET value=value+1 WHERE id=(SELECT item_id FROM financial_value_bindings LIMIT 1)");
     Integer stale=jdbc.queryForObject("SELECT count(*) FROM vw_financial_value_bindings WHERE stale_binding",Integer.class);
     if(stale==null||stale==0)throw new IllegalStateException("Changed canonical not marked stale");
     result.put("first",first);result.put("replay",second);result.put("historicalLeakage",false);result.put("negativeConstraints",negative);result.put("staleDetected",stale);
     connection.rollback();result.put("rolledBack",true);
    }else{sql.execute(ddl);connection.commit();}
   }
   result.put("status","PASS");result.put("sha256",sha);result.put("completedAt",java.time.Instant.now().toString());
   json.writerWithDefaultPrettyPrinter().writeValue(out.resolve(apply?"apply.json":"isolate.json").toFile(),result);
   System.out.println(json.writeValueAsString(result));
  }
 }
}
