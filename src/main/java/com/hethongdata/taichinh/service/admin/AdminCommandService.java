package com.hethongdata.taichinh.service.admin;

import static com.hethongdata.taichinh.config.SecurityConfiguration.currentActor;

import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.repository.jdbc.admin.AdminAuditJdbcRepository;
import com.hethongdata.taichinh.repository.jdbc.admin.AdminCommandJdbcRepository;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Dispatches existing domain services, without performing provider fetch/build logic here. */
@Service
public class AdminCommandService {
  public record Request(UUID idempotencyKey, String action, String reason, JsonNode input) {}
  private static final Set<String> WORKER_ACTIONS;
  static {
    var actions=new TreeSet<>(AdminAiActionService.ACTIONS);
    actions.addAll(List.of("RUN_JOB","BUILD_VERSION","BUILD_MARKET_PRICE_VERSION","MANUAL_INGESTION",
        "INGEST_VALIDATE_BUILD","PROCESS_RUN","REPLAY_RUN","VALIDATE_RAW","VALIDATE_PENDING"));
    WORKER_ACTIONS=Collections.unmodifiableSet(actions);
  }
  private final AdminCommandJdbcRepository repository;
  private final AdminAuditJdbcRepository auditRepository;
  private final ObjectMapper json;
  private final IngestionJobService jobs;
  private final IngestionService ingestion;
  private final ValidationJobService validation;
  private final Validator validator;
  private final TransactionTemplate tx;
  private final AdminPipelineService pipeline;
  private final AdminAiActionService ai;
  private final UUID owner=UUID.randomUUID();
  @org.springframework.beans.factory.annotation.Value("${financial.admin.commands.enabled:true}")
  private boolean enabled=true;
  public boolean workerEnabled() {return enabled;}
  private final Set<UUID> inFlight=ConcurrentHashMap.newKeySet();
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AdminCommandService.class);
  private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(3, r -> {
    var t=new Thread(r,"admin-native-command"); t.setDaemon(true); return t;
  });

  public AdminCommandService(AdminCommandJdbcRepository repository, AdminAuditJdbcRepository auditRepository,
      ObjectMapper json, IngestionJobService jobs,
      IngestionService ingestion, ValidationJobService validation, Validator validator,
      PlatformTransactionManager transactions, AdminPipelineService pipeline, AdminAiActionService ai) {
    this.repository=repository; this.auditRepository=auditRepository; this.json=json; this.jobs=jobs;
    this.ingestion=ingestion; this.pipeline=pipeline; this.ai=ai;
    this.validation=validation; this.validator=validator; this.tx=new TransactionTemplate(transactions);
  }
  @PostConstruct void start() {
    if(!enabled) return;
    workers.scheduleWithFixedDelay(this::work,2,1,TimeUnit.SECONDS);
    workers.scheduleWithFixedDelay(this::work,3,1,TimeUnit.SECONDS);
    workers.scheduleWithFixedDelay(this::leases,2,10,TimeUnit.SECONDS);
  }
  @PreDestroy void stop() { workers.shutdownNow(); }
  public Map<String,Object> submit(Request request) {
    return submit(request,null);
  }
  private Map<String,Object> submit(Request request,UUID parent) {
    if(!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Command worker disabled on this instance; use the configured operational instance");
    if (request.idempotencyKey()==null || request.reason()==null || request.reason().isBlank()
        || request.reason().length()>2000 || request.input()==null || !request.input().isObject())
      throw new IllegalArgumentException("idempotencyKey, reason (1..2000), and input object are required");
    rejectCredentials(request.input());
    String resource=resource(request.action(),request.input());
    String hash=hash(canonical(json.valueToTree(Map.of("action",request.action(),"reason",request.reason(),"input",request.input()))).toString());
    var previous=repository.findPrevious(request.idempotencyKey());
    if (!previous.isEmpty()) {
      if(!Objects.equals(parent,previous.getFirst().get("parent_command_id"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key belongs to another retry chain");
      if (!hash.equals(previous.getFirst().get("request_hash")) && !legacyHashMatches(request,previous.getFirst().get("request_hash").toString()))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key belongs to a different request");
      return find((UUID)previous.getFirst().get("id"));
    }
    UUID id=UUID.randomUUID();
    try {
      tx.executeWithoutResult(s -> {
        repository.insertQueued(id, request.idempotencyKey(), hash, request.action(), resource, request.reason(), request.input().toString(), parent, currentActor());
        event("SUBMITTED",id,request.reason());
        if(parent!=null) event("RETRIED",id,request.reason());
      });
    } catch (org.springframework.dao.DuplicateKeyException e) {
      previous=repository.findPrevious(request.idempotencyKey());
      if (!previous.isEmpty() && Objects.equals(parent,previous.getFirst().get("parent_command_id")) && hash.equals(previous.getFirst().get("request_hash"))) return find((UUID)previous.getFirst().get("id"));
      throw new ResponseStatusException(HttpStatus.CONFLICT,"This resource already has a queued, running, or unresolved command");
    }
    return find(id);
  }
  private String resource(String action, JsonNode in) {
    if (action==null) throw new IllegalArgumentException("action is required");
    return switch(action) {
      case "RUN_JOB" -> "job:"+uuid(in,"jobId");
      case "BUILD_VERSION" -> { uuid(in,"jobId"); yield "version:"+uuid(in,"versionId"); }
      case "BUILD_MARKET_PRICE_VERSION" -> { uuid(in,"jobId"); yield "version:"+uuid(in,"versionId"); }
      case "MANUAL_INGESTION", "INGEST_VALIDATE_BUILD" -> {
        var body=json.convertValue(in,ManualIngestionRequest.class);
        var errors=validator.validate(body);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.iterator().next().getPropertyPath()+" is required or invalid");
        if("INGEST_VALIDATE_BUILD".equals(action)) AdminPipelineService.builder(body.getOperation());
        var normalized=new com.hethongdata.taichinh.application.port.model.ExternalFetchRequest(body.getOperation(),body.getProvider(),body.getSymbol(),body.getStartDate(),body.getEndDate(),body.getInterval(),body.safeParameters());
        yield "ingestion:"+normalized.provider()+":"+normalized.symbol()+":"+normalized.operation();
      }
      case "REPLAY_RUN" -> resource("MANUAL_INGESTION",json.valueToTree(replayInput(uuid(in,"runId"))));
      case "PROCESS_RUN" -> "run:"+uuid(in,"runId");
      case "VALIDATE_RAW" -> "raw:"+uuid(in,"rawPayloadId");
      case "VALIDATE_PENDING" -> { limit(in); yield "validation-pending"; }
      default -> { if(!AdminAiActionService.ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported native action"); yield ai.resource(action,in); }
    };
  }
  private UUID uuid(JsonNode in,String key) { return UUID.fromString(in.path(key).asText()); }
  private ManualIngestionRequest replayInput(UUID id) {
    var rows=repository.getReplayParams(id);
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
    var rows=repository.findById(id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Command not found");
    var row=new LinkedHashMap<>(rows.getFirst());
    for(String key:List.of("request_json","result_json","progress_json")) if(row.get(key)!=null) {
      try { row.put(key,AdminConsoleReadService.safe(json.readTree(row.get(key).toString()))); }
      catch(Exception e) { row.put(key,"Invalid stored JSON"); }
    }
    return row;
  }
  public Map<String,Object> acknowledge(UUID id,String note) {
    if(note==null || note.isBlank() || note.length()>2000) throw new IllegalArgumentException("A review note is required");
    int changed=repository.acknowledge(id, note);
    if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only UNKNOWN commands can be acknowledged after checking native runs");
    return find(id);
  }
  public Map<String,Object> cancel(UUID id,String reason) {
    requireReason(reason);
    return tx.execute(s -> {
      int n=repository.cancelQueued(id, reason);
      if(n!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only queued commands can be cancelled; running native work cannot be interrupted safely");
      event("CANCELLED",id,reason);return find(id);
    });
  }
  public Map<String,Object> retry(UUID id,UUID key,String reason,boolean reviewed) {
    requireReason(reason);
    if(!reviewed) throw new IllegalArgumentException("Review the original run before explicitly retrying");
    var old=find(id);String state=old.get("status").toString();
    if(!Set.of("FAILED","CANCELLED","ACKNOWLEDGED").contains(state))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Retry requires a failed/cancelled or acknowledged command; UNKNOWN must be reviewed first");
    String action=old.get("action").toString(); JsonNode input=(JsonNode)old.get("request_json");
    var progress=(JsonNode)old.get("progress_json");
    if("INGEST_VALIDATE_BUILD".equals(action) && progress!=null && progress.hasNonNull("sourceRunId")) {
      action="PROCESS_RUN";input=json.createObjectNode().put("runId",progress.path("sourceRunId").asText());
    }
    if(key!=null && key.equals(old.get("idempotency_key"))) throw new IllegalArgumentException("Retry requires a fresh idempotency key");
    return submit(new Request(key,action,reason,input),id);
  }
  /** Records an operator note on a validation issue. The validation status itself never changes. */
  public Map<String,Object> reviewIssue(UUID validationResultId,String reason) {
    requireReason(reason);
    if(!repository.validationResultExists(validationResultId))
      throw new IllegalArgumentException("Validation result not found");
    UUID reviewId=UUID.randomUUID();
    auditRepository.recordEvent(reviewId,"ISSUE_REVIEWED",validationResultId,reason,currentActor());
    return Map.of("id",reviewId,"validationResultId",validationResultId,"validationStatusChanged",false);
  }
  private void requireReason(String reason) { if(reason==null || reason.isBlank() || reason.length()>2000) throw new IllegalArgumentException("A reason (1..2000) is required"); }
  private void event(String type,UUID id,String reason) {
    repository.recordEvent(UUID.randomUUID(),type,id,reason,currentActor());
  }
  private void work() {
    if(!enabled) return;
    try {
      Map<String,Object> command=tx.execute(s -> repository.claimQueued(owner, WORKER_ACTIONS));
      if(command==null) return;
      UUID id=(UUID)command.get("id");
      inFlight.add(id);
      try {
        JsonNode input=json.readTree(command.get("request_json").toString());
        Object result=switch(command.get("action").toString()) {
          case "RUN_JOB" -> jobs.runOnce(uuid(input,"jobId"));
          case "BUILD_VERSION" -> jobs.runVersion(uuid(input,"jobId"),uuid(input,"versionId"));
          case "BUILD_MARKET_PRICE_VERSION" -> jobs.runPriceVersion(uuid(input,"jobId"),uuid(input,"versionId"));
          case "MANUAL_INGESTION" -> ingestion.ingest(json.treeToValue(input,ManualIngestionRequest.class));
          case "INGEST_VALIDATE_BUILD" -> pipeline.execute(json.treeToValue(input,ManualIngestionRequest.class),p->progress(id,p));
          case "PROCESS_RUN" -> pipeline.processRun(uuid(input,"runId"),p->progress(id,p));
          case "REPLAY_RUN" -> Map.of("parentRunId",uuid(input,"runId"),"execution",ingestion.ingest(replayInput(uuid(input,"runId"))));
          case "VALIDATE_RAW" -> validation.validate(uuid(input,"rawPayloadId"));
          case "VALIDATE_PENDING" -> validation.validatePending(limit(input));
          default -> ai.execute(command.get("action").toString(),input);
        };
        repository.recordSuccess(id, owner, json.writeValueAsString(result));
      } catch(Exception e) {
        String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        var result=json.createObjectNode().put("exceptionType",e.getClass().getSimpleName());
        if(e instanceof IngestionExecutionException execution) {
          result.put("runId",execution.runId().toString());
          result.put("category",execution.category().name());
          if(execution.upstreamStatus()!=null) result.put("upstreamStatus",execution.upstreamStatus());
        }
        repository.recordFailure(id, owner,
            AdminConsoleReadService.safe(json.getNodeFactory().textNode(message.substring(0,Math.min(2000,message.length())))).asText(),
            result.toString());
      } finally { inFlight.remove(id); }
    } catch(Exception failure) {
      // No credential/input is logged. A lost result stops its lease and becomes UNKNOWN.
      LOG.warn("Admin command claim/result unavailable: {}",failure.getClass().getSimpleName());
    }
  }
  private void progress(UUID id,JsonNode state) {
    repository.updateProgress(id, owner, AdminConsoleReadService.safe(state).toString());
  }
  private JsonNode canonical(JsonNode node) {
    if(node.isObject()) {var out=json.createObjectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);for(String k:keys) out.set(k,canonical(node.get(k)));return out;}
    if(node.isArray()) {var out=json.createArrayNode();node.forEach(v->out.add(canonical(v)));return out;}return node;
  }
  private boolean legacyHashMatches(Request r,String existing) {
    for(var keys:List.of(List.of("action","reason","input"),List.of("action","input","reason"),List.of("reason","action","input"),List.of("reason","input","action"),List.of("input","action","reason"),List.of("input","reason","action"))) {
      var old=json.createObjectNode();for(String k:keys) old.set(k,switch(k){case "action"->json.valueToTree(r.action());case "reason"->json.valueToTree(r.reason());default->r.input();});
      if(hash(old.toString()).equals(existing)) return true;
    }return false;
  }
  private void leases() {
    if(!enabled) return;
    try {
      repository.extendLeases(inFlight, owner);
    } catch(Exception ignored) { /* DB outage is visible in console reads. */ }
  }
  private String hash(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch(Exception e) { throw new IllegalStateException(e); }
  }
}
