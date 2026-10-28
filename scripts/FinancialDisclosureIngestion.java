package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.FinancialDataApplication;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Real Spring -> configured Python proxy -> registered issuer, raw evidence only. */
public class FinancialDisclosureIngestion {
 public static void main(String[]args)throws Exception{
  Path out=Path.of(args[1]);Files.createDirectories(out);String mode=args[2];
  try(var ctx=new SpringApplicationBuilder(FinancialDataApplication.class).web(WebApplicationType.NONE).run(
   "--spring.config.additional-location=file:"+Path.of(args[0]).toAbsolutePath().toString().replace('\\','/'),
   "--spring.main.banner-mode=off","--spring.jpa.hibernate.ddl-auto=none","--spring.jpa.show-sql=false",
   "--financial.python-service.base-url="+(args.length>4?args[4]:"https://he-thong-data-tai-chinh-test.vercel.app"),"--financial.python-service.read-timeout=PT90S",
   "--financial.ingestion.scheduler.enabled=false","--financial.validation.scheduler.enabled=false",
   "--financial.security-job-reconciliation.scheduler.enabled=false","--financial.llm.scheduler.enabled=false",
   "--financial.news-recovery.scheduler.enabled=false","--financial.llm.enabled=false","--financial.local-ai.enabled=false",
   "--financial.master-data.catalog.seed-enabled=false","--financial.ingestion.catalog.seed-enabled=false",
   "--financial.validation.catalog.seed-enabled=false","--financial.llm.catalog.seed-enabled=false",
   "--financial.ingestion.retry-budget.initialize-enabled=false","--logging.level.root=ERROR")){
   var mapper=ctx.getBean(ObjectMapper.class);var ingest=ctx.getBean(IngestionService.class);var sources=ctx.getBean(DataSourceJpaRepository.class);var raws=ctx.getBean(RawPayloadJpaRepository.class);
   var source=sources.findByCodeIgnoreCase("FPT_IR").orElseGet(()->sources.saveAndFlush(DataSourceEntity.create("FPT_IR","FPT official investor disclosures","API","https://fpt.com","fpt-ir",true,"UNKNOWN")));
   if(!"fpt-ir".equals(source.getProvider())||!"https://fpt.com".equals(source.getBaseUrl()))throw new IllegalStateException("Existing FPT_IR source identity mismatch");
   if(mode.equals("metadata")){
    var quality=ctx.getBean(FinancialDataQualityService.class);var result=new LinkedHashMap<String,Object>();
    result.put("before",quality.status("FPT"));result.put("first",quality.refresh("FPT",null));
    result.put("repeat",quality.refresh("FPT",null));result.put("after",quality.status("FPT"));
    result.put("completed_at",Instant.now().toString());
    mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("metadata-receipt.json").toFile(),result);
    mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("documents.json").toFile(),ctx.getBean(FinancialDisclosureDocumentStore.class).list("FPT",100));
    System.out.println(mapper.writeValueAsString(result));return;
   }
   var docs=mode.equals("listing")?mapper.readTree("[{\"id\":\"listing\",\"path\":\"vi/nha-dau-tu/thong-tin-cong-bo\"}]"):mapper.readTree(Files.readString(Path.of(args[3])));
   var receipt=new ArrayList<Map<String,Object>>();
   for(var doc:docs){
    String symbol=doc.path("symbol").asText("FPT");var official=OfficialFinancialSources.forSymbol(symbol);
    String path=doc.path("path").asText();
    if(!(symbol.equals("FPT")&&mode.equals("listing")&&path.equals("vi/nha-dau-tu/thong-tin-cong-bo"))
      &&!official.acceptsDocument(official.documentOrigin()+"/"+path))throw new IllegalArgumentException("Unapproved disclosure path");
    if(!doc.path("id").asText().matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid output id");
    var r=new LinkedHashMap<String,Object>();r.put("document",doc);r.put("started_at",Instant.now().toString());
    try{
     var req=new ManualIngestionRequest();req.setOperation(ExternalOperation.RAW_PROXY);req.setProvider(official.provider());req.setDataSourceCode(official.code());req.setSymbol(symbol);req.setParameters(Map.of("upstream_path",path));
     var fetched=ingest.ingest(req);r.put("ingestion",mapper.valueToTree(fetched));var raw=raws.findById(fetched.getRawPayloadId()).orElseThrow();
     if(mode.equals("listing")){Files.writeString(out.resolve("listing.html"),raw.getRawText());}
     else{
      var p=raw.getPayload();if(p==null||!"financial.disclosure.binary.v1".equals(p.path("schema_version").asText()))throw new IllegalArgumentException("Source did not return a supported PDF envelope");
      byte[] bytes=Base64.getDecoder().decode(p.path("content_base64").asText());String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      if(!digest.equals(p.path("source_sha256").asText())||bytes.length!=p.path("source_bytes").asInt())throw new IllegalArgumentException("PDF raw integrity mismatch");
      Files.write(out.resolve(doc.path("id").asText()+".pdf"),bytes);r.put("source_sha256",digest);r.put("source_bytes",bytes.length);
     }
     r.put("raw_payload_id",raw.getId());r.put("status","REAL_RAW_PERSISTED");
    }catch(Exception e){r.put("status","FAILED");r.put("error_type",e.getClass().getSimpleName());}
    r.put("completed_at",Instant.now().toString());receipt.add(r);mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("pipeline_receipts.json").toFile(),receipt);System.out.println("DISCLOSURE "+doc.path("id").asText()+" "+r.get("status"));
   }
  }
 }
}
