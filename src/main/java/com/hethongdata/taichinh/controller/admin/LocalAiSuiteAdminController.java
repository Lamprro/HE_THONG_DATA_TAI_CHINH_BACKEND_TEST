package com.hethongdata.taichinh.controller.admin;
import com.hethongdata.taichinh.service.localai.LocalAiSuiteService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/admin/local-ai/suite")
public class LocalAiSuiteAdminController {
 private final LocalAiSuiteService service;
 public LocalAiSuiteAdminController(LocalAiSuiteService service){this.service=service;}
 @GetMapping("/packages") public Object packages(){return service.packages();}
 @GetMapping("/packages/{id}/inputs") public Object inputs(@PathVariable UUID id){return service.inputs(id);}
 @PostMapping("/execute") public Object execute(@RequestBody LocalAiSuiteService.Request r){return service.execute(r);}
 @GetMapping("/runs/{id}") public Object run(@PathVariable UUID id){return service.run(id);}
 @GetMapping("/packages/{id}/runs") public Object runs(@PathVariable UUID id){return service.runs(id);}
 @PostMapping("/packages/{id}/recover-stale") public Object recover(@PathVariable UUID id){return service.recover(id);}
}
