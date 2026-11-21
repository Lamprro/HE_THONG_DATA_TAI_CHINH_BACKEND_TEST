package com.hethongdata.taichinh.service.localai;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LocalAiSuiteService {
 private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;private final boolean enabled;private final String token;private final URI endpoint;private final HttpClient http;
 public record Request(UUID packageId,String modelId,String ticker,String role,UUID requestId){}
 public LocalAiSuiteService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,@Value("${financial.local-ai-suite.enabled:false}") boolean enabled,@Value("${financial.local-ai-suite.base-url:http://127.0.0.1:8794}") String base,@Value("${financial.local-ai-suite.api-token:}") String token){
  this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);this.enabled=enabled;this.token=token;URI u=URI.create(base);
  if(!Set.of("127.0.0.1","localhost","::1").contains(u.getHost())||!"http".equals(u.getScheme())||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException("SUITE_REQUIRES_LOOPBACK");
  endpoint=u.resolve("/v1/predict");http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 String encode(Object x){try{return json.writeValueAsString(x);}catch(Exception e){throw new IllegalArgumentException("INVALID_JSON");}}
 JsonNode parse(String x){try{return json.readTree(x);}catch(Exception e){throw new IllegalArgumentException("INVALID_REGISTERED_JSON");}}
 static String hash(String x){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 void requireEnabled(){if(!enabled||token.length()<32)throw new IllegalArgumentException("SUITE_NOT_CONFIGURED");}
 public Object packages(){requireEnabled();return db.queryForList("SELECT id,manifest_sha256,status,registered_at,manifest->'models' AS models FROM local_ai_packages ORDER BY registered_at DESC");}
 public Object inputs(UUID id){requireEnabled();return db.queryForList("SELECT model_id,ticker,data_as_of,input_sha256 FROM local_ai_package_inputs WHERE package_id=? ORDER BY model_id,ticker",id);}
 public Object run(UUID id){requireEnabled();var row=db.queryForMap("SELECT * FROM local_ai_package_runs WHERE id=?",id);for(String key:List.of("request_json","response_json","checks"))if(row.get(key)!=null)row.put(key,parse(row.get(key).toString()));return row;}
 public Object runs(UUID id){requireEnabled();return db.queryForList("SELECT id,request_id,model_id,ticker,role,status,error_code,http_status,latency_ms,started_at,completed_at FROM local_ai_package_runs WHERE package_id=? ORDER BY started_at DESC LIMIT 500",id);}
 Map<String,Object> result(UUID id,String status,JsonNode response){var r=new LinkedHashMap<String,Object>();r.put("runId",id);r.put("status",status);r.put("response",response);return r;}
 public Object execute(Request req){
  requireEnabled();if(req==null||req.packageId()==null||req.requestId()==null||req.modelId()==null||!req.modelId().matches("[a-z0-9_]{2,60}")||req.ticker()==null||!req.ticker().matches("[A-Z0-9]{2,12}")||!Set.of("selected","challenger","baseline").contains(req.role()==null?"":req.role()))throw new IllegalArgumentException("INVALID_SUITE_REQUEST");
  var packages=db.queryForList("SELECT manifest,manifest_sha256 FROM local_ai_packages WHERE id=? AND status='RESEARCH_ONLY'",req.packageId());if(packages.size()!=1)throw new IllegalArgumentException("PACKAGE_NOT_REGISTERED");
  var pkg=packages.getFirst();JsonNode manifest=parse(pkg.get("manifest").toString()),spec=null;for(var m:manifest.path("models"))if(m.path("id").asText().equals(req.modelId()))spec=m;
  if(spec==null)throw new IllegalArgumentException("MODEL_NOT_REGISTERED");
  String digest=hash(req.packageId()+"|"+pkg.get("manifest_sha256")+"|"+req.modelId()+"|"+req.ticker()+"|"+req.role());UUID id=UUID.randomUUID();Instant start=Instant.now();
  int claim=db.update("INSERT INTO local_ai_package_runs(id,request_id,package_id,model_id,ticker,role,request_hash,status) VALUES(?,?,?,?,?,?,?,'RUNNING') ON CONFLICT DO NOTHING",id,req.requestId(),req.packageId(),req.modelId(),req.ticker(),req.role(),digest);
  if(claim==0){
   var prior=db.queryForMap("SELECT id,request_hash,status,response_json FROM local_ai_package_runs WHERE package_id=? AND request_id=? AND cached_from IS NULL",req.packageId(),req.requestId());
   boolean cached=digest.equals(prior.get("request_hash"))&&"SUCCEEDED".equals(prior.get("status"));String status=cached?"CACHED":"INPUT_REJECTED",error=cached?null:!digest.equals(prior.get("request_hash"))?"IDEMPOTENCY_CONFLICT":"REQUEST_IN_PROGRESS_OR_FAILED";String response=cached?prior.get("response_json").toString():null;
   db.update("INSERT INTO local_ai_package_runs(id,request_id,package_id,model_id,ticker,role,request_hash,status,cached_from,response_json,error_code,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,now())",id,req.requestId(),req.packageId(),req.modelId(),req.ticker(),req.role(),digest,status,prior.get("id"),response,error);return result(id,status,response==null?null:parse(response));
  }
  Integer httpStatus=null;long nanos=System.nanoTime();String raw=null;boolean called=false;
  try{
   var rows=db.queryForList("SELECT input_payload,input_sha256,data_as_of FROM local_ai_package_inputs WHERE package_id=? AND model_id=? AND ticker=?",req.packageId(),req.modelId(),req.ticker());if(rows.size()!=1)throw new IllegalArgumentException("NO_VERIFIED_FEATURE_SNAPSHOT");var input=rows.getFirst();String payload=input.get("input_payload").toString(),inputHash=input.get("input_sha256").toString();
   if(!hash(payload).equals(inputHash))throw new IllegalArgumentException("REGISTERED_INPUT_HASH_MISMATCH");
   var body=new LinkedHashMap<String,Object>();body.put("schema","local_ai.registered.input.v1");body.put("package_id",req.packageId().toString());body.put("manifest_sha256",pkg.get("manifest_sha256").toString());body.put("model_id",req.modelId());body.put("role",req.role());body.put("ticker",req.ticker());body.put("input_sha256",inputHash);body.put("input",parse(payload));String request=encode(body);
   db.update("UPDATE local_ai_package_runs SET request_json=?::jsonb WHERE id=?",request,id);called=true;
   var reply=http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request)).build(),HttpResponse.BodyHandlers.ofString());httpStatus=reply.statusCode();raw=reply.body();
   if(httpStatus!=200||raw.length()>200000)throw new IllegalStateException("PROVIDER_HTTP_OR_SIZE");JsonNode output=parse(raw);var checks=LocalAiSuiteContract.validate(output,spec,req.packageId().toString(),pkg.get("manifest_sha256").toString(),req.role(),req.ticker(),inputHash,input.get("data_as_of").toString(),start);String checked=encode(checks);
   db.update("UPDATE local_ai_package_runs SET checks=?::jsonb WHERE id=?",checked,id);if(!Boolean.TRUE.equals(checks.get("passed")))throw new IllegalStateException("OUTPUT_VALIDATION_FAILED");long latency=(System.nanoTime()-nanos)/1000000;
   tx.executeWithoutResult(s->{
    db.update("UPDATE local_ai_package_runs SET status='SUCCEEDED',http_status=200,latency_ms=?,response_json=?::jsonb,checks=?::jsonb,completed_at=now() WHERE id=?",latency,encode(output),checked,id);
    db.update("INSERT INTO local_ai_package_predictions(id,run_id,package_id,model_id,ticker,role,target_period,issued_at,prediction,readiness) VALUES(?,?,?,?,?,?,?,?,?::jsonb,'RESEARCH_ONLY')",UUID.randomUUID(),id,req.packageId(),req.modelId(),req.ticker(),req.role(),output.path("result").path("target_period").asText(),OffsetDateTime.parse(output.path("issued_at").asText()),encode(output.path("result")));
   });return result(id,"SUCCEEDED",output);
  }catch(Exception e){
   if(e instanceof InterruptedException)Thread.currentThread().interrupt();String error=e instanceof IllegalArgumentException||e instanceof IllegalStateException?e.getMessage():"PROVIDER_OR_PERSISTENCE_FAILURE";String status=called?"PROVIDER_FAILED":"INPUT_REJECTED";String diagnostic=null;try{if(raw!=null&&raw.length()<=200000)diagnostic=encode(json.readTree(raw));}catch(Exception ignored){}
   db.update("UPDATE local_ai_package_runs SET status=?,http_status=?,latency_ms=?,response_json=?::jsonb,error_code=?,completed_at=now() WHERE id=?",status,httpStatus,(System.nanoTime()-nanos)/1000000,diagnostic,error,id);return result(id,status,null);
  }
 }
 public Object recover(UUID pkg){requireEnabled();return db.queryForList("UPDATE local_ai_package_runs SET status='PROVIDER_FAILED',error_code='STALE_RUN_AUDIT_INCOMPLETE',completed_at=now() WHERE package_id=? AND status='RUNNING' AND started_at<now()-interval '5 minutes' RETURNING id,status,error_code",pkg);}
}
