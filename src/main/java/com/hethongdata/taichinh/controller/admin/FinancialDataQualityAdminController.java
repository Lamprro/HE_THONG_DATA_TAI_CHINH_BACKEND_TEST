package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.financial.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/financial-data")
public class FinancialDataQualityAdminController {
 private final FinancialDataQualityService service;private final FinancialDisclosureDocumentStore documents;
 private final FinancialValueBindingService bindings;
 private final FinancialReconciliationEvidenceService reviews;
 public FinancialDataQualityAdminController(FinancialDataQualityService service,FinancialDisclosureDocumentStore documents,FinancialValueBindingService bindings,FinancialReconciliationEvidenceService reviews){this.service=service;this.documents=documents;this.bindings=bindings;this.reviews=reviews;}
 public record RefreshRequest(String symbol,Integer year,Integer page){}
 @PostMapping("/disclosures/refresh") public Map<String,Object> refresh(@RequestBody RefreshRequest request){return service.refresh(request.symbol(),request.year(),request.page()==null?1:request.page());}
 @GetMapping("/disclosures") public List<Map<String,Object>> list(@RequestParam String symbol,@RequestParam(defaultValue="100") int limit){return documents.list(symbol,limit);}
 @GetMapping("/status") public Map<String,Object> status(@RequestParam String symbol){return service.status(symbol);}
 @GetMapping("/coverage") public List<Map<String,Object>> coverage(){return service.coverage();}
 @GetMapping("/reconciliation") public List<Map<String,Object>> reconciliation(@RequestParam String symbol){return reviews.list(symbol);}
 @PostMapping("/bindings/reconcile") public Map<String,Object> reconcile(@RequestBody RefreshRequest request){return bindings.reconcile(request.symbol());}
 @GetMapping("/bindings") public List<Map<String,Object>> bindings(@RequestParam String symbol,
   @RequestParam java.time.Instant asOf,@RequestParam(defaultValue="100") int limit){return bindings.list(symbol,asOf,limit);}
}
