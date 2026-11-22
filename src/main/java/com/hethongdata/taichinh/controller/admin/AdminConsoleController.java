package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;
import com.hethongdata.taichinh.service.admin.*;
import com.hethongdata.taichinh.service.llm.NewsLlmService;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/console")
public class AdminConsoleController {
  private final AdminConsoleReadService reads;
  private final AdminCommandService commands;
  private final AdminRuntimeService runtime;
  private final NewsLlmService news;

  public AdminConsoleController(AdminConsoleReadService reads, AdminCommandService commands,
      AdminRuntimeService runtime, NewsLlmService news) {
    this.reads = reads;
    this.commands = commands;
    this.runtime = runtime;
    this.news = news;
  }

  @GetMapping("/session")
  public Session session(Authentication authentication) {
    String email = authentication.getPrincipal() instanceof Jwt principal
        ? principal.getClaimAsString("email") : null;
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
  @PostMapping("/commands") public ResponseEntity<Command> submit(@RequestBody CommandRequest body) {
    return ResponseEntity.accepted().body(commands.submit(body));
  }
  @GetMapping("/commands/{id}") public Command command(@PathVariable UUID id) { return commands.find(id); }
  @PostMapping("/commands/{id}/acknowledge") public Command acknowledge(@PathVariable UUID id,@RequestBody ReasonRequest body) {
    return commands.acknowledge(id,body.reason());
  }
  @PostMapping("/commands/{id}/cancel") public Command cancel(@PathVariable UUID id,@RequestBody ReasonRequest body) {
    return commands.cancel(id,body.reason());
  }
  @PostMapping("/commands/{id}/retry") public ResponseEntity<Command> retry(@PathVariable UUID id,@RequestBody RetryRequest body) {
    return ResponseEntity.accepted().body(commands.retry(id,body.idempotencyKey(),body.reason(),body.reviewed()));
  }
  @GetMapping("/summary") public Summary summary() { return reads.summary(); }
  @GetMapping("/news-candidates") public List<NewsCandidate> newsCandidates(@RequestParam(defaultValue="20") int limit) {
    if(limit<1||limit>100) throw new IllegalArgumentException("limit must be 1..100");
    return reads.articleTitles(news.candidates(limit));
  }
  @GetMapping("/issues/{id}/reviews") public List<IssueReview> reviews(@PathVariable UUID id) { return reads.issueReviews(id); }
  @PostMapping("/issues/{id}/reviews") public ReviewReceipt reviewIssue(@PathVariable UUID id,@RequestBody ReasonRequest body, Authentication authentication) {
    return reads.reviewIssue(id, body.reason(), authentication.getName());
  }
}
