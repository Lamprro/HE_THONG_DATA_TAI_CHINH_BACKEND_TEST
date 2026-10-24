import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Rehearse the forecast migration against a copy before applying the same SQL. */
public class ForecastPeriodMigration {
 public static void main(String[] args)throws Exception {
  var cfg=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]))){cfg.load(r);}
  Path file=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
  String sql=Files.readString(file),sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  boolean apply=args[3].equals("apply");var json=new ObjectMapper();
  if(apply){var receipt=json.readTree(Files.readString(out.resolve("isolate.json")));if(!sha.equals(receipt.path("sha256").asText())||!"PASS".equals(receipt.path("status").asText()))throw new IllegalStateException("Matching isolated rehearsal required");}
  try(var c=DriverManager.getConnection(cfg.getProperty("spring.datasource.url"),cfg.getProperty("spring.datasource.username"),cfg.getProperty("spring.datasource.password"));var s=c.createStatement()){
   c.setAutoCommit(false);s.execute("SET LOCAL lock_timeout='5s'");s.execute("SET LOCAL statement_timeout='60s'");
   if(!apply){String schema="forecast_migration_"+UUID.randomUUID().toString().replace("-","");s.execute("CREATE SCHEMA "+schema);s.execute("SET LOCAL search_path TO "+schema);s.execute("CREATE TABLE llm_results (LIKE public.llm_results INCLUDING ALL)");s.execute("INSERT INTO llm_results SELECT * FROM public.llm_results");}
   s.execute(sql);s.execute(sql);
   if(apply)c.commit();else c.rollback();
   json.writerWithDefaultPrettyPrinter().writeValue(out.resolve(apply?"apply.json":"isolate.json").toFile(),Map.of("status","PASS","sha256",sha,"completedAt",java.time.Instant.now().toString(),"mode",args[3]));
   System.out.println("FORECAST_MIGRATION_"+args[3].toUpperCase()+"_PASS");
  }
 }
}
