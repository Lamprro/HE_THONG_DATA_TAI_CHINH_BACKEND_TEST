package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;
import com.hethongdata.taichinh.service.admin.*;
import com.hethongdata.taichinh.service.llm.NewsLlmService;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/console")
public class AdminConsoleController {
  private final AdminConsoleReadService reads;
  private final AdminCommandService commands;
  private final AdminRuntimeService runtime;
  private final AdminScheduleService schedule;
  private final NewsLlmService news;

  public AdminConsoleController(AdminConsoleReadService reads, AdminCommandService commands,
      AdminRuntimeService runtime, AdminScheduleService schedule, NewsLlmService news) {
    this.reads = reads;
    this.commands = commands;
    this.runtime = runtime;
    this.schedule = schedule;
    this.news = news;
  }

  @GetMapping("/session")
  public Session session(Authentication authentication) {
    String email = authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal
        ? principal.getAttribute("email") : null;
    return new Session(authentication.isAuthenticated(), authentication.getName(), email,
        authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
  }

  @GetMapping("/runtime")
  public RuntimeStatus runtime() {
    return runtime.status();
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
  @GetMapping("/summary") public Map<String,Object> summary() { return reads.summary(); }
  @GetMapping("/schedule") public Map<String,Object> schedule() {return schedule.status();}
  @GetMapping("/news-candidates") public List<Map<String,Object>> newsCandidates(@RequestParam(defaultValue="20") int limit) {
    if(limit<1||limit>100) throw new IllegalArgumentException("limit must be 1..100");
    return reads.articleTitles(news.candidates(limit));
  }
  @PutMapping("/schedule") public Map<String,Object> updateSchedule(@RequestBody AdminScheduleService.Request request) {return schedule.update(request);}
  @GetMapping("/issues/{id}/reviews") public List<Map<String,Object>> reviews(@PathVariable UUID id) { return reads.issueReviews(id); }
  @PostMapping("/issues/{id}/reviews") public Map<String,Object> reviewIssue(@PathVariable UUID id,@RequestBody Map<String,String> body) {
    return commands.reviewIssue(id,body.get("reason"));
  }
}
