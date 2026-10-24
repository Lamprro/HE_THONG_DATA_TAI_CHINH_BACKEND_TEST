package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.FinancialDataApplication;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.model.*;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.integration.python.PythonFinancialDataProperties;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Calls the configured real ingestion pipeline; parses evidence without promoting canonical data. */
public class FinancialMetadataRecall {
 public static void main(String[] args)throws Exception {
  Path out=Path.of(args[1]);Files.createDirectories(out);
  try(var ctx=new SpringApplicationBuilder(FinancialDataApplication.class).web(WebApplicationType.NONE).run(
   "--spring.config.additional-location=file:"+Path.of(args[0]).toAbsolutePath().toString().replace('\\','/'),
   "--spring.main.banner-mode=off","--spring.jpa.hibernate.ddl-auto=none","--spring.jpa.show-sql=false",
   "--financial.python-service.read-timeout=PT65S",
   "--financial.python-service.base-url="+(args.length>3?args[3]:"https://he-thong-data-tai-chinh-test.vercel.app"),
   "--financial.ingestion.scheduler.enabled=false","--financial.validation.scheduler.enabled=false",
   "--financial.security-job-reconciliation.scheduler.enabled=false","--financial.llm.scheduler.enabled=false",
   "--financial.news-recovery.scheduler.enabled=false","--financial.llm.enabled=false","--financial.local-ai.enabled=false",
   "--financial.master-data.catalog.seed-enabled=false","--financial.ingestion.catalog.seed-enabled=false",
   "--financial.validation.catalog.seed-enabled=false","--financial.llm.catalog.seed-enabled=false",
   "--financial.ingestion.retry-budget.initialize-enabled=false","--logging.level.root=OFF")) {
   var mapper=ctx.getBean(ObjectMapper.class);var ingest=ctx.getBean(IngestionService.class);
   var raws=ctx.getBean(RawPayloadJpaRepository.class);var builder=ctx.getBean(FinancialStatementBuildService.class);
   var validation=ctx.getBean(ValidationJobService.class);var receipt=new ArrayList<Map<String,Object>>();
   var runtime=new LinkedHashMap<String,Object>();runtime.put("started_at",Instant.now().toString());
   runtime.put("python_base_url",ctx.getBean(PythonFinancialDataProperties.class).getBaseUrl().toString());
   try {var health=ctx.getBean(ExternalFinancialDataPort.class).fetch(new ExternalFetchRequest(ExternalOperation.HEALTH,null,null,null,null,null,Map.of()));runtime.put("health_http",health.httpStatus());runtime.put("health",mapper.readTree(health.rawBody()));}
   catch(Exception e){runtime.put("health_error",e.getClass().getSimpleName());}
   mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("runtime.json").toFile(),runtime);
   for(var request:mapper.readTree(Files.readString(Path.of(args[2])))) {
    var record=new LinkedHashMap<String,Object>();String id=request.path("id").asText();
    record.put("request",request);record.put("started_at",Instant.now().toString());
    try {
     var req=new ManualIngestionRequest();req.setOperation(ExternalOperation.FINANCIAL_STATEMENT);
     req.setProvider(request.path("provider").asText());req.setSymbol(request.path("symbol").asText());
     Map<String,String> params=new LinkedHashMap<>();request.path("parameters").fields().forEachRemaining(e->params.put(e.getKey(),e.getValue().asText()));req.setParameters(params);
     var fetched=ingest.ingest(req);record.put("ingestion",mapper.valueToTree(fetched));
     var raw=raws.findById(fetched.getRawPayloadId()).orElseThrow();
     mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve(id+"-raw.json").toFile(),raw.getPayload());
     record.put("validation",mapper.valueToTree(validation.validate(raw.getId())));
     var parsed=new ArrayList<Map<String,Object>>();
     for(var d:builder.parse(raw)) {
      var row=new LinkedHashMap<String,Object>();row.put("period",d.period());row.put("statement_type",d.statementType());row.put("metadata",d.metadata());row.put("items",d.items());parsed.add(row);
     }
     mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve(id+"-parsed.json").toFile(),parsed);
     record.put("parsed_periods",parsed.size());record.put("status","FETCHED_VALIDATED_PARSED");
    } catch(Exception e) {
     record.put("status","FAILED");record.put("error_type",e.getClass().getSimpleName());
     if(e instanceof IngestionExecutionException ie){record.put("run_id",ie.runId());record.put("category",ie.category());record.put("upstream_status",ie.upstreamStatus());}
     else if(e instanceof IllegalArgumentException)record.put("detail",e.getMessage());
    }
    record.put("completed_at",Instant.now().toString());receipt.add(record);
    mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("pipeline_receipts.json").toFile(),receipt);
    System.out.println(id+" "+record.get("status"));
   }
  }
 }
}
