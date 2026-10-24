package com.hethongdata.taichinh.service.financial;
import com.hethongdata.taichinh.FinancialDataApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;import java.net.*;import java.net.http.*;import java.util.*;
public class FinancialMetadataHttpSmoke {
 public static void main(String[] args)throws Exception {
  Path out=Path.of(args[1]);Files.createDirectories(out);String token=UUID.randomUUID().toString()+UUID.randomUUID();  try(var ctx=new SpringApplicationBuilder(FinancialDataApplication.class).web(WebApplicationType.SERVLET).run(
   "--spring.config.additional-location=file:"+Path.of(args[0]).toAbsolutePath().toString().replace('\\','/'),
   "--spring.main.banner-mode=off","--server.address=127.0.0.1","--server.port=0","--financial.admin.api-token="+token,"--spring.jpa.hibernate.ddl-auto=none","--spring.jpa.show-sql=false",
   "--financial.python-service.read-timeout=PT65S",
   "--financial.python-service.base-url="+args[2],
   "--financial.ingestion.scheduler.enabled=false","--financial.validation.scheduler.enabled=false",
   "--financial.security-job-reconciliation.scheduler.enabled=false","--financial.llm.scheduler.enabled=false",
   "--financial.news-recovery.scheduler.enabled=false","--financial.llm.enabled=false","--financial.local-ai.enabled=false",
   "--financial.master-data.catalog.seed-enabled=false","--financial.ingestion.catalog.seed-enabled=false",
   "--financial.validation.catalog.seed-enabled=false","--financial.llm.catalog.seed-enabled=false",
   "--financial.ingestion.retry-budget.initialize-enabled=false","--logging.level.root=OFF")) {
   var mapper=ctx.getBean(ObjectMapper.class);var client=HttpClient.newHttpClient();
   int port=((ServletWebServerApplicationContext)ctx).getWebServer().getPort();String base="http://127.0.0.1:"+port+"/api/admin/financial-data";
   var result=new LinkedHashMap<String,Object>();
   for(String path:List.of("/status?symbol=FPT","/disclosures?symbol=FPT&limit=100")){
    var denied=client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    if(denied.statusCode()!=403)throw new IllegalStateException("UNAUTHENTICATED_NOT_DENIED");
    var ok=client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    if(ok.statusCode()!=200)throw new IllegalStateException("READ_HTTP_"+ok.statusCode());
    result.put(path,Map.of("deniedStatus",403,"allowedStatus",200,"body",mapper.readTree(ok.body())));
   }
   var refresh=client.send(HttpRequest.newBuilder(URI.create(base+"/disclosures/refresh")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"symbol\":\"FPT\",\"year\":2025}")).build(),HttpResponse.BodyHandlers.ofString());
   if(refresh.statusCode()!=200)throw new IllegalStateException("REFRESH_HTTP_"+refresh.statusCode());
   result.put("refresh",mapper.readTree(refresh.body()));result.put("completed_at",java.time.Instant.now().toString());
   mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("http-smoke.json").toFile(),result);System.out.println("REAL_HTTP_API_PASS");
  }
 }
}