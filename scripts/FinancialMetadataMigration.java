import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Isolate first, then apply precisely the checked additive migration. Never logs credentials. */
public class FinancialMetadataMigration {
 static String resolve(String text){if(text!=null&&text.startsWith("${")&&text.endsWith("}")){String v=text.substring(2,text.length()-1);int i=v.indexOf(':');return System.getenv().getOrDefault(i<0?v:v.substring(0,i),i<0?"":v.substring(i+1));}return text;}
 public static void main(String[] args)throws Exception {
  Path config=Path.of(args[0]),migration=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);boolean apply=args[3].equals("apply");
  var p=new Properties();try(var r=Files.newBufferedReader(config)){p.load(r);}var json=new ObjectMapper();
  String ddl=Files.readString(migration),sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(migration)));
  if(apply){var proof=json.readTree(Files.readString(out.resolve("migration-isolate.json")));if(!"PASS".equals(proof.path("status").asText())||!sha.equals(proof.path("migration_sha256").asText()))throw new IllegalStateException("ISOLATION_RECEIPT_REQUIRED");}
  try(var c=DriverManager.getConnection(resolve(p.getProperty("spring.datasource.url")),resolve(p.getProperty("spring.datasource.username")),resolve(p.getProperty("spring.datasource.password")))) {
   c.setAutoCommit(false);String schema="financial_metadata_test_"+UUID.randomUUID().toString().replace("-","");var receipt=new LinkedHashMap<String,Object>();
   try(var s=c.createStatement()){
    s.execute("SET LOCAL lock_timeout='5s'");s.execute("SET LOCAL statement_timeout='30s'");
    if(!apply){s.execute("CREATE SCHEMA "+schema);s.execute("SET LOCAL search_path TO "+schema);for(String table:List.of("financial_statements","raw_payloads","data_sources"))s.execute("CREATE TABLE "+table+" (LIKE public."+table+" INCLUDING ALL)");}
    s.execute(ddl);s.execute(ddl);
    if(!apply){
     // Existing source raw is copied only into this rollback-only schema for FK checks.
     s.execute("INSERT INTO raw_payloads SELECT * FROM public.raw_payloads LIMIT 1");
     String insert="INSERT INTO financial_disclosure_documents(source_code,symbol,document_id,record_sha256,listing_sha256,source_raw_payload_id,period_end,published_on,available_at,report_scope,revision_status,report_version_id,metadata) SELECT 'FPT_IR','FPT','isolated',repeat('a',64),repeat('b',64),id,'2025-06-30','2025-07-22','2025-07-22 23:59:59.999999+07','CONSOLIDATED','UNKNOWN','FPT_IR:isolated','{}'::jsonb FROM raw_payloads LIMIT 1 ON CONFLICT DO NOTHING";
     if(s.executeUpdate(insert)!=1||s.executeUpdate(insert)!=0)throw new IllegalStateException("IDEMPOTENCY_FAILED");
     int rejected=0;for(String change:List.of("training_eligible=true","published_on='2025-06-01'","revision_status='ORIGINAL'","report_scope='ALL'")){
      var sp=c.setSavepoint();try{s.executeUpdate("UPDATE financial_disclosure_documents SET "+change);throw new IllegalStateException("NEGATIVE_ACCEPTED");}catch(SQLException e){if(!"23514".equals(e.getSQLState()))throw e;rejected++;}finally{c.rollback(sp);}
     }receipt.put("negative_constraints_rejected",rejected);c.rollback();receipt.put("schema_rolled_back",schema);
    }else c.commit();
   }receipt.put("status","PASS");receipt.put("migration_sha256",sha);receipt.put("completed_at",java.time.Instant.now().toString());receipt.put("mode",apply?"apply":"isolate");
   json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("migration-"+(apply?"apply":"isolate")+".json").toFile(),receipt);System.out.println(json.writeValueAsString(receipt));
  }catch(SQLException e){System.err.println("DATABASE_FAILURE SQLSTATE="+e.getSQLState());System.exit(2);}
 }
}
