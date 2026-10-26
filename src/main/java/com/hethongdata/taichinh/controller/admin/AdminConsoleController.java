package com.hethongdata.taichinh.controller.admin;

import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.service.admin.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@RestController
@RequestMapping("/api/admin/console")
public class AdminConsoleController {
  private final AdminConsoleReadService reads;
  private final AdminCommandService commands;
  private final RequestMappingHandlerMapping routes;
  private final ObjectMapper json;
  private final Environment environment;
  private final JdbcTemplate db;
  @org.springframework.beans.factory.annotation.Autowired private AdminScheduleService schedule;
  @org.springframework.beans.factory.annotation.Autowired private NewsLlmAdminController news;
  public AdminConsoleController(AdminConsoleReadService reads, AdminCommandService commands,
      @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping routes,
      ObjectMapper json, Environment environment, JdbcTemplate db) {
    this.reads=reads; this.commands=commands; this.routes=routes; this.json=json; this.environment=environment; this.db=db;
  }
  @GetMapping("/session") public Map<String,Object> session() {
    return Map.of("authenticated",true,"principal","DEDICATED_ADMIN_TOKEN","humanIdentityVerified",false,
        "capabilities",List.of("ALL_JOBS","NATIVE_API_CATALOG","PAGED_DATA_BROWSER","DURABLE_COMMANDS"));
  }
  @GetMapping("/runtime") public Map<String,Object> runtime() {
    var schedulers=new LinkedHashMap<String,Object>();
    for(String name:List.of("ingestion","macro","validation","security-job-reconciliation","llm","news-recovery"))
      schedulers.put(name,Map.of("enabled", environment.getProperty("financial."+name+".scheduler.enabled",Boolean.class,false),
          "evidence","configuration only; no heartbeat is inferred"));
    return Map.of("database",db.queryForObject("select 1",Integer.class)==1?"UP":"DOWN", "schedulers",schedulers,
        "timezone","Asia/Ho_Chi_Minh","nativeApiCount",catalog().size(),
        "adminWorkers",Map.of("commandsEnabled",commands.workerEnabled(),"scheduleEnabled",schedule.workerEnabled(),"scope","this instance only; other instances may still process shared work"),
        "commandSemantics","SUCCEEDED means the native call returned; inspect result status and final data versions. UNKNOWN must be reviewed before retry.");
  }
  @GetMapping("/datasets") public List<Map<String,Object>> datasets() { return reads.tables(); }
  @GetMapping("/data/{dataset}") public Map<String,Object> data(@PathVariable String dataset,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,
      @RequestParam(required=false) String search,@RequestParam(required=false) String runId,
      @RequestParam(required=false) String status,@RequestParam Map<String,String> parameters) {
    var exact=new LinkedHashMap<String,String>(parameters);
    for(String framework:List.of("page","size","search","runId","status")) exact.remove(framework);
    return reads.browse(dataset,page,size,search,runId,status,exact);
  }
  @PostMapping("/commands") public ResponseEntity<Map<String,Object>> submit(@RequestBody AdminCommandService.Request body) {
    return ResponseEntity.accepted().body(commands.submit(body));
  }
  @GetMapping("/commands/{id}") public Map<String,Object> command(@PathVariable UUID id) { return commands.find(id); }
  @PostMapping("/commands/{id}/acknowledge") public Map<String,Object> acknowledge(@PathVariable UUID id,@RequestBody Map<String,String> body) {
    return commands.acknowledge(id,body.get("reason"));
  }
  @PostMapping("/commands/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id,@RequestBody Map<String,String> body) {
    return commands.cancel(id,body.get("reason"));
  }
  public record RetryRequest(UUID idempotencyKey,String reason,boolean reviewed) {}
  @PostMapping("/commands/{id}/retry") public ResponseEntity<Map<String,Object>> retry(@PathVariable UUID id,@RequestBody RetryRequest body) {
    return ResponseEntity.accepted().body(commands.retry(id,body.idempotencyKey(),body.reason(),body.reviewed()));
  }
  @GetMapping("/summary") public Map<String,Object> summary() {
    return Map.of("jobs",db.queryForList("select is_active,count(*) as count from ingestion_jobs group by is_active"),
      "commands",db.queryForList("select status,count(*) as count from admin_commands group by status"),
      "runs",db.queryForList("select status,count(*) as count from ingestion_runs group by status"),
      "versions",db.queryForList("select data_domain,status,count(*) as count from data_versions group by data_domain,status"),
      "validation",db.queryForList("select validation_target,result_status,count(*) as count from validation_results group by validation_target,result_status"));
  }
  @GetMapping("/schedule") public Map<String,Object> schedule() {return schedule.status();}
  @GetMapping("/news-candidates") public List<Map<String,Object>> newsCandidates(@RequestParam(defaultValue="20") int limit) {
    if(limit<1||limit>100) throw new IllegalArgumentException("limit must be 1..100");
    var ids=news.candidates(limit);if(ids.isEmpty()) return List.of();
    String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
    var rows=db.queryForList("select id as \"articleId\",title,published_at from news_articles where id in ("+placeholders+")",ids.toArray());
    rows.sort(Comparator.comparingInt(row->ids.indexOf(row.get("articleId"))));return rows;
  }
  @PutMapping("/schedule") public Map<String,Object> updateSchedule(@RequestBody AdminScheduleService.Request request) {return schedule.update(request);}
  @GetMapping("/issues/{id}/reviews") public List<Map<String,Object>> reviews(@PathVariable UUID id) {
    return db.queryForList("select id,event_type,reason,principal,created_at from admin_operation_events where target_id=? order by created_at desc,id desc",id);
  }
  @PostMapping("/issues/{id}/reviews") public Map<String,Object> reviewIssue(@PathVariable UUID id,@RequestBody Map<String,String> body) {
    String reason=body.get("reason");
    if(reason==null || reason.isBlank() || reason.length()>2000) throw new IllegalArgumentException("A review reason (1..2000) is required");
    if(db.queryForObject("select count(*) from validation_results where id=?",Integer.class,id)!=1) throw new IllegalArgumentException("Validation result not found");
    UUID reviewId=UUID.randomUUID();
    db.update("insert into admin_operation_events(id,event_type,target_id,reason) values (?,'ISSUE_REVIEWED',?,?)",reviewId,id,reason);
    return Map.of("id",reviewId,"validationResultId",id,"validationStatusChanged",false);
  }
  /** Discovers the deployed MVC contract; a future controller is visible without pretending it is deployed today. */
  @GetMapping("/catalog") public List<Map<String,Object>> catalog() {
    var entries=new ArrayList<Map<String,Object>>();
    routes.getHandlerMethods().forEach((mapping,handler) -> {
      for(String path:mapping.getPatternValues()) {
        if(path.startsWith("/api/admin/console")) continue;
        if(!(path.startsWith("/api/admin/") || List.of("/api/ingestion-jobs","/api/ingestions","/api/raw-payloads",
            "/api/data-sources","/api/companies","/api/securities","/api/external-financial-data/health",
            "/api/external-financial-data/providers","/api/external-financial-data/capabilities").stream().anyMatch(path::startsWith))) continue;
        for(var method:mapping.getMethodsCondition().getMethods()) {
          var inputs=new ArrayList<Map<String,Object>>();
          for(var parameter:handler.getMethodParameters()) {
            var p=parameter.getParameterAnnotation(PathVariable.class);
            var q=parameter.getParameterAnnotation(RequestParam.class);
            var b=parameter.getParameterAnnotation(RequestBody.class);
            if(p==null && q==null && b==null) continue;
            String name=parameter.getParameterName();
            if(p!=null && !p.value().isBlank()) name=p.value();
            if(q!=null && !q.value().isBlank()) name=q.value();
            var field=new LinkedHashMap<String,Object>();
            field.put("name",name==null?"body":name); field.put("location",b!=null?"body":p!=null?"path":"query");
            field.put("required",p!=null || (q!=null && q.required() && q.defaultValue().equals(ValueConstants.DEFAULT_NONE)) || (b!=null && b.required()));
            field.putAll(schema(json.constructType(parameter.getGenericParameterType()),0));
            if(q!=null && !q.defaultValue().equals(ValueConstants.DEFAULT_NONE)) field.put("defaultValue",q.defaultValue());
            inputs.add(field);
          }
          entries.add(Map.of("id",method+" "+path,"method",method.name(),"path",path,
              "operation",handler.getMethod().getName(),"controller",handler.getBeanType().getSimpleName(),"inputs",inputs));
        }
      }
    });
    entries.sort(Comparator.comparing(e -> e.get("id").toString())); return entries;
  }
  private Map<String,Object> schema(JavaType type,int depth) {
    Class<?> raw=type.getRawClass(); var out=new LinkedHashMap<String,Object>();
    if(raw.isEnum()) { out.put("type","string"); out.put("enum",Arrays.stream(raw.getEnumConstants()).map(Object::toString).toList()); }
    else if(raw==boolean.class || raw==Boolean.class) out.put("type","boolean");
    else if(Number.class.isAssignableFrom(raw) || (raw.isPrimitive() && raw!=char.class)) out.put("type","number");
    else if(raw==UUID.class || raw==String.class || raw.getName().startsWith("java.time.")) {
      out.put("type","string"); if(raw==UUID.class) out.put("format","uuid");
      if(raw==java.time.LocalDate.class) out.put("format","date");
    } else if(type.isCollectionLikeType() || raw.isArray()) {
      out.put("type","array"); if(type.getContentType()!=null) out.put("items",schema(type.getContentType(),depth+1));
    } else {
      out.put("type","object");
      if(depth<2 && !Map.class.isAssignableFrom(raw) && !JsonNode.class.isAssignableFrom(raw)) {
        var fields=new ArrayList<Map<String,Object>>();
        for(var prop:json.getDeserializationConfig().introspect(type).findProperties()) {
          var f=new LinkedHashMap<String,Object>(schema(prop.getPrimaryType(),depth+1)); f.put("name",prop.getName()); fields.add(f);
        }
        out.put("fields",fields);
      }
    }
    return out;
  }
}
