package com.hethongdata.taichinh.service.financial;
import com.hethongdata.taichinh.FinancialDataApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;import java.net.*;import java.net.http.*;import java.util.*;
public class FinancialDataAcceptance {
 public static void main(String[] args)throws Exception {
  Path out=Path.of(args[1]);Files.createDirectories(out);String token=UUID.randomUUID().toString()+UUID.randomUUID();  try(var ctx=new SpringApplicationBuilder(FinancialDataApplication.class).web(WebApplicationType.SERVLET).run(
   "--spring.config.additional-location=file:"+Path.of(args[0]).toAbsolutePath().toString().replace('\\','/'),
   "--spring.main.banner-mode=off","--server.address=127.0.0.1","--server.port=0","--financial.admin.api-token="+token,"--spring.jpa.hibernate.ddl-auto="+(args.length>4?"validate":"none"),"--spring.profiles.active="+(args.length>4?"production":"default"),"--spring.jpa.show-sql=false",
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
   for(String path:List.of("/status?symbol=FPT","/disclosures?symbol=HPG&limit=100","/coverage","/bindings?symbol=FPT&asOf=2025-01-01T00:00:00Z","/reconciliation?symbol=FPT")){
    var denied=client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    if(denied.statusCode()!=403)throw new IllegalStateException("UNAUTHENTICATED_NOT_DENIED");
    var ok=client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    if(ok.statusCode()!=200)throw new IllegalStateException("READ_HTTP_"+ok.statusCode());
    result.put(path,Map.of("deniedStatus",403,"allowedStatus",200,"body",mapper.readTree(ok.body())));
   }
   if(args.length>4) {
    String url="http://127.0.0.1:"+port+"/api/securities";
    var denied=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString());
    if(denied.statusCode()!=403)throw new IllegalStateException("PRODUCTION_NON_ADMIN_API_NOT_PROTECTED");
    var ok=client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    if(ok.statusCode()!=200)throw new IllegalStateException("PRODUCTION_AUTHENTICATED_READ_FAILED");
    result.put("productionSecurity",Map.of("nonAdminDeniedStatus",403,"nonAdminAllowedStatus",200));
   }
   var calls=new ArrayList<Map<String,Object>>();
   for(var input:mapper.readTree(Files.readString(Path.of(args[3])))){
    var call=new LinkedHashMap<String,Object>();call.put("request",input);
    try {
     var refresh=client.send(HttpRequest.newBuilder(URI.create(base+"/disclosures/refresh")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),HttpResponse.BodyHandlers.ofString());
     call.put("status",refresh.statusCode());call.put("response",mapper.readTree(refresh.body()));
     System.out.println("SOURCE "+input+" HTTP "+refresh.statusCode());
    }catch(Exception e){call.put("error",e.getClass().getSimpleName());}
    calls.add(call);mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("source-calls.json").toFile(),calls);
   }
   result.put("sources",calls);
   var binding=ctx.getBean(FinancialValueBindingService.class);
   result.put("bindings",binding.reconcile("FPT"));result.put("bindingsRepeat",binding.reconcile("FPT"));
   result.put("coverage",ctx.getBean(FinancialDataQualityService.class).coverage());
   result.put("historicalBindings",binding.list("FPT",java.time.Instant.parse("2025-01-01T00:00:00Z"),500));
   result.put("completed_at",java.time.Instant.now().toString());
   mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("http-smoke.json").toFile(),result);if(calls.stream().anyMatch(c->!Integer.valueOf(200).equals(c.get("status"))))throw new IllegalStateException("SOURCE_ACCEPTANCE_INCOMPLETE; see source-calls.json");
   System.out.println("REAL_HTTP_API_PASS");
  }
 }
}