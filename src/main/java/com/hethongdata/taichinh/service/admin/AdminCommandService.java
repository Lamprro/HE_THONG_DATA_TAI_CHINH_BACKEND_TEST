package com.hethongdata.taichinh.service.admin;

import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Dispatches existing domain services, without performing provider fetch/build logic here. */
@Service
public class AdminCommandService {
  public record Request(UUID idempotencyKey, String action, String reason, JsonNode input) {}
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final IngestionJobService jobs;
  private final IngestionService ingestion;
  private final ValidationJobService validation;
  private final Validator validator;
  private final TransactionTemplate tx;
  private final UUID owner=UUID.randomUUID();
  private final Set<UUID> inFlight=ConcurrentHashMap.newKeySet();
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AdminCommandService.class);
  private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(3, r -> {
    var t=new Thread(r,"admin-native-command"); t.setDaemon(true); return t;
  });
  public AdminCommandService(JdbcTemplate db, ObjectMapper json, IngestionJobService jobs,
      IngestionService ingestion, ValidationJobService validation, Validator validator,
      PlatformTransactionManager transactions) {
    this.db=db; this.json=json; this.jobs=jobs; this.ingestion=ingestion;
    this.validation=validation; this.validator=validator; this.tx=new TransactionTemplate(transactions);
  }
  @PostConstruct void start() {
    workers.scheduleWithFixedDelay(this::work,2,1,TimeUnit.SECONDS);
    workers.scheduleWithFixedDelay(this::work,3,1,TimeUnit.SECONDS);
    workers.scheduleWithFixedDelay(this::leases,2,10,TimeUnit.SECONDS);
  }
  @PreDestroy void stop() { workers.shutdownNow(); }
  public Map<String,Object> submit(Request request) {
    if (request.idempotencyKey()==null || request.reason()==null || request.reason().isBlank()
        || request.reason().length()>2000 || request.input()==null || !request.input().isObject())
      throw new IllegalArgumentException("idempotencyKey, reason (1..2000), and input object are required");
    rejectCredentials(request.input());
    String resource=resource(request.action(),request.input());
    String hash=hash(json.valueToTree(Map.of("action",request.action(),"reason",request.reason(),"input",request.input())).toString());
    var previous=db.queryForList("select id,request_hash from admin_commands where idempotency_key=?",request.idempotencyKey());
    if (!previous.isEmpty()) {
      if (!hash.equals(previous.getFirst().get("request_hash")))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key belongs to a different request");
      return find((UUID)previous.getFirst().get("id"));
    }
    UUID id=UUID.randomUUID();
    try {
      db.update("insert into admin_commands(id,idempotency_key,request_hash,action,resource_key,reason,status,request_json) "
          +"values (?,?,?,?,?,?,'QUEUED',cast(? as jsonb))",id,request.idempotencyKey(),hash,
          request.action(),resource,request.reason(),request.input().toString());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      previous=db.queryForList("select id,request_hash from admin_commands where idempotency_key=?",request.idempotencyKey());
      if (!previous.isEmpty() && hash.equals(previous.getFirst().get("request_hash"))) return find((UUID)previous.getFirst().get("id"));
      throw new ResponseStatusException(HttpStatus.CONFLICT,"This resource already has a queued, running, or unresolved command");
    }
    return find(id);
  }
  private String resource(String action, JsonNode in) {
    if (action==null) throw new IllegalArgumentException("action is required");
    return switch(action) {
      case "RUN_JOB" -> "job:"+uuid(in,"jobId");
      case "BUILD_MARKET_PRICE_VERSION" -> { uuid(in,"jobId"); yield "version:"+uuid(in,"versionId"); }
      case "MANUAL_INGESTION" -> {
        var body=json.convertValue(in,ManualIngestionRequest.class);
        var errors=validator.validate(body);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.iterator().next().getPropertyPath()+" is required or invalid");
        yield "ingestion:"+body.getProvider()+":"+body.getSymbol()+":"+body.getOperation();
      }
      case "REPLAY_RUN" -> { uuid(in,"runId"); replayInput(uuid(in,"runId")); yield "replay:"+uuid(in,"runId"); }
      case "VALIDATE_RAW" -> "raw:"+uuid(in,"rawPayloadId");
      case "VALIDATE_PENDING" -> { limit(in); yield "validation-pending"; }
      default -> throw new IllegalArgumentException("Unsupported native command action");
    };
  }
  private UUID uuid(JsonNode in,String key) { return UUID.fromString(in.path(key).asText()); }
  private ManualIngestionRequest replayInput(UUID id) {
    var rows=db.queryForList("select r.request_query_params::text,s.code from ingestion_runs r "
        +"join data_sources s on s.id=r.data_source_id where r.id=?",id);
    if(rows.isEmpty()) throw new IllegalArgumentException("Original ingestion run not found");
    try {
      var input=json.readTree(rows.getFirst().get("request_query_params").toString());
      if(!input.hasNonNull("operation")) throw new IllegalArgumentException("Internal builder runs must be replayed using their job; inspect current job parameters first");
      var body=new ManualIngestionRequest();
      body.setOperation(com.hethongdata.taichinh.application.port.model.ExternalOperation.valueOf(input.path("operation").asText()));
      body.setDataSourceCode(rows.getFirst().get("code").toString());
      body.setProvider(input.path("provider").asText(null)); body.setSymbol(input.path("symbol").asText(null));
      if(!input.path("startDate").asText("").isBlank()) body.setStartDate(java.time.LocalDate.parse(input.path("startDate").asText()));
      if(!input.path("endDate").asText("").isBlank()) body.setEndDate(java.time.LocalDate.parse(input.path("endDate").asText()));
      body.setInterval(input.path("interval").asText(null));
      if(input.path("parameters").isObject()) body.setParameters(json.convertValue(input.path("parameters"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));
      return body;
    } catch(IllegalArgumentException e) { throw e; }
    catch(Exception e) { throw new IllegalArgumentException("Original request is not replayable"); }
  }
  private int limit(JsonNode in) {
    int limit=in.path("limit").asInt(50);
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100"); return limit;
  }
  private void rejectCredentials(JsonNode node) {
    if(node.isObject()) node.fields().forEachRemaining(e -> {
      if(AdminConsoleReadService.sensitive(e.getKey())) throw new IllegalArgumentException("Credentials must not be included in operation input");
      rejectCredentials(e.getValue());
    }); else if(node.isArray()) node.forEach(this::rejectCredentials);
  }
  public Map<String,Object> find(UUID id) {
    var rows=db.queryForList("select id,idempotency_key,action,resource_key,reason,principal,status,request_json::text,"
        +"result_json::text,error_message,created_at,started_at,finished_at,acknowledgement_note from admin_commands where id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Command not found");
    var row=new LinkedHashMap<>(rows.getFirst());
    for(String key:List.of("request_json","result_json")) if(row.get(key)!=null) {
      try { row.put(key,AdminConsoleReadService.safe(json.readTree(row.get(key).toString()))); }
      catch(Exception e) { row.put(key,"Invalid stored JSON"); }
    }
    return row;
  }
  public Map<String,Object> acknowledge(UUID id,String note) {
    if(note==null || note.isBlank() || note.length()>2000) throw new IllegalArgumentException("A review note is required");
    int changed=db.update("update admin_commands set status='ACKNOWLEDGED', acknowledgement_note=? "
        +"where id=? and status='UNKNOWN'",note,id);
    if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only UNKNOWN commands can be acknowledged after checking native runs");
    return find(id);
  }
  private void work() {
    try {
      Map<String,Object> command=tx.execute(s -> {
        var rows=db.queryForList("select id,action,request_json::text from admin_commands where status='QUEUED' "
            +"order by created_at,id for update skip locked limit 1");
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        db.update("update admin_commands set status='RUNNING',owner_id=?,started_at=now(),lease_until=now()+interval '90 seconds' where id=?",owner,row.get("id"));
        return row;
      });
      if(command==null) return;
      UUID id=(UUID)command.get("id");
      inFlight.add(id);
      try {
        JsonNode input=json.readTree(command.get("request_json").toString());
        Object result=switch(command.get("action").toString()) {
          case "RUN_JOB" -> jobs.runOnce(uuid(input,"jobId"));
          case "BUILD_MARKET_PRICE_VERSION" -> jobs.runPriceVersion(uuid(input,"jobId"),uuid(input,"versionId"));
          case "MANUAL_INGESTION" -> ingestion.ingest(json.treeToValue(input,ManualIngestionRequest.class));
          case "REPLAY_RUN" -> Map.of("parentRunId",uuid(input,"runId"),"execution",ingestion.ingest(replayInput(uuid(input,"runId"))));
          case "VALIDATE_RAW" -> validation.validate(uuid(input,"rawPayloadId"));
          case "VALIDATE_PENDING" -> validation.validatePending(limit(input));
          default -> throw new IllegalArgumentException("Unknown persisted action");
        };
        db.update("update admin_commands set status='SUCCEEDED',result_json=cast(? as jsonb),finished_at=now() "
            +"where id=? and status='RUNNING' and owner_id=?",json.writeValueAsString(result),id,owner);
      } catch(Exception e) {
        String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        var result=json.createObjectNode().put("exceptionType",e.getClass().getSimpleName());
        if(e instanceof IngestionExecutionException execution) {
          result.put("runId",execution.runId().toString());
          result.put("category",execution.category().name());
          if(execution.upstreamStatus()!=null) result.put("upstreamStatus",execution.upstreamStatus());
        }
        db.update("update admin_commands set status='FAILED',error_message=?,result_json=cast(? as jsonb),finished_at=now() "
            +"where id=? and status='RUNNING' and owner_id=?",
            AdminConsoleReadService.safe(json.getNodeFactory().textNode(message.substring(0,Math.min(2000,message.length())))).asText(),result.toString(),id,owner);
      } finally { inFlight.remove(id); }
    } catch(Exception failure) {
      // No credential/input is logged. A lost result stops its lease and becomes UNKNOWN.
      LOG.warn("Admin command claim/result unavailable: {}",failure.getClass().getSimpleName());
    }
  }
  private void leases() {
    try {
      for(UUID id:inFlight) db.update("update admin_commands set lease_until=now()+interval '90 seconds' where id=? and status='RUNNING' and owner_id=?",id,owner);
      db.update("update admin_commands set status='UNKNOWN',finished_at=now(),error_message='Worker lease expired. Check original native runs before retrying.' "
          +"where status='RUNNING' and lease_until<now()");
    } catch(Exception ignored) { /* DB outage is visible in console reads. */ }
  }
  private String hash(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch(Exception e) { throw new IllegalStateException(e); }
  }
}
